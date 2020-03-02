/*
 * Copyright (C) 2019 Oliver Jedinger
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 3 of the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with this program; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin Street, Fifth Floor, Boston, MA  02110-1301, USA.
 *
 */
package com.github.mc1arke.sonarqube.plugin.ce.pullrequest.bitbucket.server;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.mc1arke.sonarqube.plugin.ce.pullrequest.AnalysisDetails;
import com.github.mc1arke.sonarqube.plugin.ce.pullrequest.PostAnalysisIssueVisitor;
import com.github.mc1arke.sonarqube.plugin.ce.pullrequest.PullRequestBuildStatusDecorator;
import com.github.mc1arke.sonarqube.plugin.ce.pullrequest.UnifyConfiguration;
import com.github.mc1arke.sonarqube.plugin.ce.pullrequest.bitbucket.HttpUtils;
import com.github.mc1arke.sonarqube.plugin.ce.pullrequest.bitbucket.server.response.activity.Activity;
import com.github.mc1arke.sonarqube.plugin.ce.pullrequest.bitbucket.server.response.activity.ActivityPage;
import com.github.mc1arke.sonarqube.plugin.ce.pullrequest.bitbucket.server.response.activity.Comment;
import com.github.mc1arke.sonarqube.plugin.ce.pullrequest.bitbucket.server.response.diff.Diff;
import com.github.mc1arke.sonarqube.plugin.ce.pullrequest.bitbucket.server.response.diff.DiffLine;
import com.github.mc1arke.sonarqube.plugin.ce.pullrequest.bitbucket.server.response.diff.DiffPage;
import com.github.mc1arke.sonarqube.plugin.ce.pullrequest.bitbucket.server.response.diff.Hunk;
import com.github.mc1arke.sonarqube.plugin.ce.pullrequest.bitbucket.server.response.diff.Segment;
import com.github.mc1arke.sonarqube.plugin.ce.pullrequest.markup.MarkdownFormatterFactory;
import org.apache.commons.io.IOUtils;
import org.apache.commons.lang.StringUtils;
import org.apache.http.HttpEntity;
import org.apache.http.HttpResponse;
import org.apache.http.client.methods.HttpDelete;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.entity.ContentType;
import org.apache.http.entity.StringEntity;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.util.EntityUtils;
import org.sonar.api.issue.Issue;
import org.sonar.api.utils.log.Logger;
import org.sonar.api.utils.log.Loggers;
import org.sonar.core.issue.DefaultIssue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

public class BitbucketServerPullRequestDecorator implements PullRequestBuildStatusDecorator {

    public static final String PULL_REQUEST_BITBUCKET_URL = "com.github.mc1arke.sonarqube.plugin.branch.pullrequest.bitbucket.server.url";

    public static final String PULL_REQUEST_BITBUCKET_TOKEN = "com.github.mc1arke.sonarqube.plugin.branch.pullrequest.bitbucket.server.token";

    public static final String PULL_REQUEST_BITBUCKET_PROJECT_KEY = "sonar.pullrequest.bitbucket.server.projectKey";

    public static final String PULL_REQUEST_BITBUCKET_USER_SLUG = "sonar.pullrequest.bitbucket.server.userSlug";

    public static final String PULL_REQUEST_BITBUCKET_REPOSITORY_SLUG = "sonar.pullrequest.bitbucket.server.repositorySlug";

    public static final String PULL_REQUEST_BITBUCKET_COMMENT_USER_SLUG = "com.github.mc1arke.sonarqube.plugin.branch.pullrequest.bitbucket.server.comment.userSlug";

    private static final Logger LOGGER = Loggers.get(BitbucketServerPullRequestDecorator.class);
    private static final List<String> OPEN_ISSUE_STATUSES =
            Issue.STATUSES.stream().filter(s -> !Issue.STATUS_CLOSED.equals(s) && !Issue.STATUS_RESOLVED.equals(s))
                    .collect(Collectors.toList());

    private static final String REST_API = "/rest/api/1.0/";
    private static final String USER_PR_API = "users/%s/repos/%s/pull-requests/%s/";
    private static final String PROJECT_PR_API = "projects/%s/repos/%s/pull-requests/%s/";
    private static final String COMMENTS_API = "comments";
    private static final String DIFF_API = "diff";
    private static final String ACTIVITIES = "activities?limit=%s";

    private static final String FULL_PR_COMMENT_API = "%s" + REST_API + PROJECT_PR_API + COMMENTS_API;
    private static final String FULL_PR_COMMENT_USER_API = "%s" + REST_API + USER_PR_API + COMMENTS_API;

    private static final String FULL_PR_ACTIVITIES_API = "%s" + REST_API + PROJECT_PR_API + ACTIVITIES;
    private static final String FULL_PR_ACTIVITIES_USER_API = "%s" + REST_API + USER_PR_API + ACTIVITIES;

    private static final String FULL_PR_DIFF_API = "%s" + REST_API + PROJECT_PR_API + DIFF_API;
    private static final String FULL_PR_DIFF_USER_API = "%s" + REST_API + USER_PR_API + DIFF_API;

    @Override
    public void decorateQualityGateStatus(AnalysisDetails analysisDetails, UnifyConfiguration configuration) {
        LOGGER.info("starting to analyze with " + analysisDetails.toString());

        try {
            final String hostURL = configuration.getRequiredServerProperty(PULL_REQUEST_BITBUCKET_URL);
            final String apiToken = configuration.getRequiredServerProperty(PULL_REQUEST_BITBUCKET_TOKEN);
            final String repositorySlug = configuration.getRequiredProperty(PULL_REQUEST_BITBUCKET_REPOSITORY_SLUG);
            final String pullRequestId = analysisDetails.getBranchName();
            final String userSlug = configuration.getProperty(PULL_REQUEST_BITBUCKET_USER_SLUG).orElse(StringUtils.EMPTY);
            final String projectKey = configuration.getProperty(PULL_REQUEST_BITBUCKET_PROJECT_KEY).orElse(StringUtils.EMPTY);
            final String commentUserSlug = configuration.getServerProperty(PULL_REQUEST_BITBUCKET_COMMENT_USER_SLUG).orElse(StringUtils.EMPTY);

            final boolean summaryCommentEnabled = Boolean.parseBoolean(configuration.getRequiredServerProperty(PULL_REQUEST_COMMENT_SUMMARY_ENABLED));
            final boolean fileCommentEnabled = Boolean.parseBoolean(configuration.getRequiredServerProperty(PULL_REQUEST_FILE_COMMENT_ENABLED));
            final boolean deleteCommentsEnabled = Boolean.parseBoolean(configuration.getRequiredServerProperty(PULL_REQUEST_DELETE_COMMENTS_ENABLED));

            final String commentUrl;
            final String activityUrl;
            final String diffUrl;
            if (StringUtils.isNotBlank(userSlug)) {
                commentUrl = String.format(FULL_PR_COMMENT_USER_API, hostURL, userSlug, repositorySlug, pullRequestId);
                diffUrl = String.format(FULL_PR_DIFF_USER_API, hostURL, userSlug, repositorySlug, pullRequestId);
                activityUrl = String.format(FULL_PR_ACTIVITIES_USER_API, hostURL, userSlug, repositorySlug, pullRequestId, 250);
            } else if (StringUtils.isNotBlank(projectKey)) {
                commentUrl = String.format(FULL_PR_COMMENT_API, hostURL, projectKey, repositorySlug, pullRequestId);
                diffUrl = String.format(FULL_PR_DIFF_API, hostURL, projectKey, repositorySlug, pullRequestId);
                activityUrl = String.format(FULL_PR_ACTIVITIES_API, hostURL, projectKey, repositorySlug, pullRequestId, 250);
            } else {
                throw new IllegalStateException(String.format("Property userSlug (%s) for /user repo or projectKey (%s) for /projects repo needs to be set.", PULL_REQUEST_BITBUCKET_USER_SLUG, PULL_REQUEST_BITBUCKET_PROJECT_KEY));
            }
            LOGGER.debug(String.format("Comment URL is: %s ", commentUrl));
            LOGGER.debug(String.format("Activity URL is: %s ", activityUrl));
            LOGGER.debug(String.format("Diff URL is: %s ", diffUrl));

            Map<String, String> headers = new HashMap<>();
            headers.put("Authorization", String.format("Bearer %s", apiToken));
            headers.put("Accept", "application/json");

            deleteComments(activityUrl, commentUrl, commentUserSlug, headers, deleteCommentsEnabled);
            String analysisSummary = analysisDetails.createAnalysisSummary(new MarkdownFormatterFactory());
            StringEntity summaryCommentEntity = new StringEntity(new ObjectMapper().writeValueAsString(new SummaryComment(analysisSummary)), ContentType.APPLICATION_JSON);
            postComment(commentUrl, headers, summaryCommentEntity, summaryCommentEnabled);

            DiffPage diffPage = HttpUtils.getPage(diffUrl, headers, DiffPage.class);
            List<PostAnalysisIssueVisitor.ComponentIssue> componentIssues = getOpenComponentIssues(analysisDetails);
            for (PostAnalysisIssueVisitor.ComponentIssue componentIssue : componentIssues) {
                final DefaultIssue issue = componentIssue.getIssue();
                String analysisIssueSummary = analysisDetails.createAnalysisIssueSummary(componentIssue, new MarkdownFormatterFactory());
                String issuePath = analysisDetails.getSCMPathForIssue(componentIssue).orElse(StringUtils.EMPTY);
                int issueLine = issue.getLine() != null ? issue.getLine() : 0;
                String issueType = getIssueType(diffPage, issuePath, issueLine);
                String fileType = "TO";
                if (issueType.equals("CONTEXT")) {
                    fileType = "FROM";
                }
                StringEntity fileCommentEntity = new StringEntity(
                        new ObjectMapper().writeValueAsString(new FileComment(analysisIssueSummary, new Anchor(issueLine, issueType, issuePath, fileType))), ContentType.APPLICATION_JSON
                );
                postComment(commentUrl, headers, fileCommentEntity, fileCommentEnabled);
            }
        } catch (IOException ex) {
            throw new IllegalStateException("Could not decorate Pull Request on Bitbucket Server", ex);
        }

    }

    protected List<PostAnalysisIssueVisitor.ComponentIssue> getOpenComponentIssues(AnalysisDetails analysisDetails) {
        return analysisDetails.getPostAnalysisIssueVisitor().getIssues().stream().filter(i -> OPEN_ISSUE_STATUSES.contains(i.getIssue().status())).collect(Collectors.toList());
    }

    protected String getIssueType(DiffPage diffPage, String issuePath, int issueLine) {
        String issueType = "CONTEXT";
        List<Diff> diffs = diffPage.getDiffs().stream()
                .filter(diff -> diff.getDestination() != null)
                .filter(diff -> issuePath.equals(diff.getDestination().getToString()))
                .collect(Collectors.toList());

        if (!diffs.isEmpty()) {
            for (Diff diff : diffs) {
                List<Hunk> hunks = diff.getHunks();
                if (!hunks.isEmpty()) {
                    issueType = getExtractIssueType(issueLine, issueType, hunks);
                }
            }
        }
        return issueType;
    }

    private String getExtractIssueType(int issueLine, String issueType, List<Hunk> hunks) {
        for (Hunk hunk : hunks) {
            List<Segment> segments = hunk.getSegments();
            for (Segment segment : segments) {
                Optional<DiffLine> optionalLine = segment.getLines().stream().filter(diffLine -> diffLine.getDestination() == issueLine).findFirst();
                if (optionalLine.isPresent()) {
                    issueType = segment.getType();
                    break;
                }
            }
        }
        return issueType;
    }

    protected boolean deleteComments(String activityUrl, String commentUrl, String userSlug, Map<String, String> headers, boolean deleteCommentsEnabled) {
        if (!deleteCommentsEnabled) {
            return false;
        }
        if (StringUtils.isEmpty(userSlug)) {
            LOGGER.info("No comments deleted cause property comment.userSlug is not set.");
            return false;
        }
        boolean commentsRemoved = false;
        final ActivityPage activityPage = HttpUtils.getPage(activityUrl, headers, ActivityPage.class);
        if (activityPage != null) {
            final List<Comment> commentsToDelete = getCommentsToDelete(userSlug, activityPage);
            LOGGER.debug(String.format("Deleting %s comments", commentsToDelete));
            for (Comment comment : commentsToDelete) {
                try {
                    boolean commentDeleted = deleteComment(commentUrl, headers, comment);
                    if (commentDeleted) {
                        commentsRemoved = true;
                    }
                } catch (IOException ex) {
                    LOGGER.error("Could not delete comment from Bitbucket Server", ex);
                }
            }

        }
        return commentsRemoved;
    }

    private boolean deleteComment(String commentUrl, Map<String, String> headers, Comment comment) throws IOException {
        boolean commentDeleted = false;
        String deleteCommentUrl = commentUrl + "/%s?version=%s";
        HttpDelete httpDelete = new HttpDelete(String.format(deleteCommentUrl, comment.getId(), comment.getVersion()));
        LOGGER.debug("delete " + comment.getId() + " " + comment.getVersion());
        for (Map.Entry<String, String> entry : headers.entrySet()) {
            httpDelete.addHeader(entry.getKey(), entry.getValue());
        }
        try (CloseableHttpClient closeableHttpClient = HttpClients.createDefault()) {
            HttpResponse deleteResponse = closeableHttpClient.execute(httpDelete);
            if (null == deleteResponse) {
                LOGGER.error("HttpResponse for deleting comment was null");
            } else if (deleteResponse.getStatusLine().getStatusCode() != 204) {
                LOGGER.error(IOUtils.toString(deleteResponse.getEntity().getContent(), StandardCharsets.UTF_8.name()));
                LOGGER.error("An error was returned in the response from the Bitbucket API. See the previous log messages for details");
            } else {
                LOGGER.debug(String.format("Comment %s version %s deleted", comment.getId(), comment.getVersion()));
                commentDeleted = true;
            }
        }
        return commentDeleted;
    }

    protected List<Comment> getCommentsToDelete(String userSlug, ActivityPage activityPage) {
        return Arrays.stream(activityPage.getValues())
                .filter(a -> a.getComment() != null)
                .filter(a -> a.getComment().getAuthor() != null)
                .filter(a -> userSlug.equals(a.getComment().getAuthor().getSlug()))
                .map(Activity::getComment)
                .collect(Collectors.toList());
    }

    protected boolean postComment(String commentUrl, Map<String, String> headers, StringEntity requestEntity, boolean sendRequest) throws IOException {
        boolean commentPosted = false;
        HttpPost httpPost = new HttpPost(commentUrl);
        for (Map.Entry<String, String> entry : headers.entrySet()) {
            httpPost.addHeader(entry.getKey(), entry.getValue());
        }
        httpPost.setEntity(requestEntity);
        LOGGER.debug(EntityUtils.toString(requestEntity));
        if (sendRequest) {
            try (CloseableHttpClient closeableHttpClient = HttpClients.createDefault()) {
                HttpResponse httpResponse = closeableHttpClient.execute(httpPost);
                if (null == httpResponse) {
                    LOGGER.error("HttpResponse for posting comment was null");
                } else if (httpResponse.getStatusLine().getStatusCode() != 201) {
                    HttpEntity entity = httpResponse.getEntity();
                    LOGGER.error(IOUtils.toString(entity.getContent(), StandardCharsets.UTF_8.name()));
                } else {
                    HttpEntity entity = httpResponse.getEntity();
                    LOGGER.debug(IOUtils.toString(entity.getContent(), StandardCharsets.UTF_8.name()));
                    commentPosted = true;
                }
            }
        }
        return commentPosted;
    }

    @Override
    public String name() {
        return "BitbucketServer";
    }
}
