package com.checkmarx.sonar.sensor.execution;

import java.util.LinkedList;
import java.util.List;

import org.sonar.api.batch.fs.InputFile;
import org.sonar.api.batch.sensor.SensorContext;
import org.sonar.api.batch.sensor.issue.NewIssue;
import org.sonar.api.batch.sensor.issue.NewIssueLocation;

import com.checkmarx.sonar.logger.CxLogger;
import com.checkmarx.sonar.sensor.dto.CxResultToSonarResult;
import com.cx.restclient.sast.dto.CxXMLResults;

/**
 * Created by: zoharby.
 * Date: 23/08/2017.
 * <p>
 * creates Issue locations that will be presented in sonar UI on the presented project code
 */
class FileIssueLocationsCreator {

    private CxLogger logger = new CxLogger(FileIssueLocationsCreator.class);

    private InputFile file;
    private final static String LINE = " line: ";
    private final static String SEMI_COLON_FILE = " ; file: ";

    FileIssueLocationsCreator(InputFile file) {
        this.file = file;
    }

    List<NewIssueLocation> createFlowLocations(CxResultToSonarResult result,SensorContext context) {
        List<NewIssueLocation> allLocationsInFile = new LinkedList<>();

        try {
            NewIssueLocation firstLocationInFile = null;
            List<CxXMLResults.Query.Result.Path.PathNode> resultsNodes = result.getResultData().getPath().getPathNode();

            //locations iteration will be from end to start because sonar inserts the list in that order
            int nodeLoopEndIdx = 0;
            int nodeLoopStartIdx = resultsNodes.size() - 1;

            //if the first node in result is not within the scanned file
            if (!CxSonarFilePathUtil.isCxPathAndSonarPathTheSame(resultsNodes.get(0).getFileName(), file.absolutePath())) {
                logger.debug("Creating highlight for the first location in file:");
                //a message stating the location of the first node
                String msg = " ; Origin - file: " + resultsNodes.get(0).getFileName() + LINE + resultsNodes.get(0).getLine();
                //find the first node that do appear in the file, create location for it, and add to it the above message
                for (CxXMLResults.Query.Result.Path.PathNode node : result.getResultData().getPath().getPathNode()) {
                    if (CxSonarFilePathUtil.isCxPathAndSonarPathTheSame(node.getFileName(), file.absolutePath())) {
                        firstLocationInFile = createLocationFromPathNode(node,context);
                        if (firstLocationInFile == null) {
                            continue;
                        }
                        //set index to the node that comes after the first location
                        nodeLoopEndIdx = resultsNodes.indexOf(node) + 1;

                        if (!CxSonarFilePathUtil.isCxPathAndSonarPathTheSame(resultsNodes.get(nodeLoopEndIdx).getFileName(), file.absolutePath())) {
                            msg = msg + " ; Next location: " + resultsNodes.get(nodeLoopEndIdx).getName() + SEMI_COLON_FILE +
                                    resultsNodes.get(nodeLoopEndIdx).getFileName() + LINE + resultsNodes.get(nodeLoopEndIdx).getLine();
                            ++nodeLoopEndIdx;
                        }
                        firstLocationInFile.message(node.getName() + msg);
                        break;
                    }
                }
            }

            logger.debug("Creating highlight for locations:");
            //set isPrevInFile as true to stay within legal index in resultsNodes
            boolean isPrevNodeInFile = true;
            boolean isCurrNodeInFile = CxSonarFilePathUtil.isCxPathAndSonarPathTheSame(resultsNodes.get(nodeLoopStartIdx).getFileName(), file.absolutePath());

            //iteration from end to start because sonar inserts the list in that order
            iterateFromEndToStart(nodeLoopStartIdx, nodeLoopEndIdx, resultsNodes, isCurrNodeInFile, isPrevNodeInFile, allLocationsInFile,context);
            addFileLocation(firstLocationInFile, allLocationsInFile);


        } catch (Exception e) {
            logger.warn("Could not highlight locations for vulnerability: " + result.getQuery().getName() + " on file: " + file.absolutePath());
            logger.warn("due to exception: " + e.getMessage());
        }

        return allLocationsInFile;
    }

    private void addFileLocation(NewIssueLocation firstLocationInFile, List<NewIssueLocation> allLocationsInFile) {
        if (firstLocationInFile != null) {
            allLocationsInFile.add(firstLocationInFile);
        }
    }

    private void iterateFromEndToStart(int nodeLoopStartIdx, int nodeLoopEndIdx, List<CxXMLResults.Query.Result.Path.PathNode> resultsNodes, boolean isCurrNodeInFile, boolean isPrevNodeInFile, List<NewIssueLocation> allLocationsInFile,SensorContext context) {
        boolean isNextNodeInFile;
        for (int i = nodeLoopStartIdx; i >= nodeLoopEndIdx; --i) {
            //set isNextNodeInFile as true in last node to stay within legal index in resultsNodes
            isNextNodeInFile = i <= 0 || CxSonarFilePathUtil.isCxPathAndSonarPathTheSame(resultsNodes.get(i - 1).getFileName(), file.absolutePath());
            CxXMLResults.Query.Result.Path.PathNode currNode = resultsNodes.get(i);
            if (isCurrNodeInFile) {
                NewIssueLocation defaultIssueLocation = createLocationFromPathNode(currNode,context);
                if (defaultIssueLocation == null) {
                    isCurrNodeInFile = isNextNodeInFile;
                    continue;
                }
                //next and prev in messages are to be opposites to next and prev in loop booleans(because iteration is end to start)
                String msgPrev = isNextNodeInFile ? "" : " ; Previous location: " + resultsNodes.get(i - 1).getName() + SEMI_COLON_FILE +
                        resultsNodes.get(i - 1).getFileName() + LINE + resultsNodes.get(i - 1).getLine();
                String msgNext = isPrevNodeInFile ? "" : " ; Next location: " + resultsNodes.get(i + 1).getName() + SEMI_COLON_FILE +
                        resultsNodes.get(i + 1).getFileName() + LINE + resultsNodes.get(i + 1).getLine();
                String msg = currNode.getName() + msgPrev + msgNext;
                allLocationsInFile.add(defaultIssueLocation.message(msg));

                isPrevNodeInFile = true;
            } else {
                isPrevNodeInFile = false;
            }
            isCurrNodeInFile = isNextNodeInFile;
        }
    }

    NewIssueLocation createIssueLocation(CxResultToSonarResult result,SensorContext context) {
        CodeHighlightsUtil.Highlight highlightLine = CodeHighlightsUtil.getHighlightForPathNode(file, result.getNodeToMarkOnFile());
        if (highlightLine == null) {
            highlightLine = new CodeHighlightsUtil.Highlight(1, -1, -1);
        }
        NewIssue defaultIssueLocation = context.newIssue();

        return defaultIssueLocation.newLocation().on(file)
                .at(file.selectLine(highlightLine.getLine()))
                .message("Checkmarx Vulnerability : " + result.getQuery().getName());
    }

    private NewIssueLocation createLocationFromPathNode(CxXMLResults.Query.Result.Path.PathNode node,SensorContext context) {
        CodeHighlightsUtil.Highlight highlight = CodeHighlightsUtil.getHighlightForPathNode(file, node);
        if (highlight == null) {
            return null;
        }
        logger.debug("File " + file.toString() + ", " + highlight.toString());
        NewIssue defaultIssueLocation = context.newIssue();

        if (highlight.getStart() == -1) {
            if (highlight.getLine() <= 1) {
                return defaultIssueLocation.newLocation().on(file);
            }
            return defaultIssueLocation.newLocation().on(file)
                    .at(file.selectLine(highlight.getLine()));
        }
        return defaultIssueLocation.newLocation().on(file)
                .at(file.newRange(file.newPointer(highlight.getLine(), highlight.getStart()),
                        file.newPointer(highlight.getLine(), highlight.getEnd())));
    }

}