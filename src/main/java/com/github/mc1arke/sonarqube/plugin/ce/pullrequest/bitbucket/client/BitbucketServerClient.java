/*
 * Copyright (C) 2020 Mathias Åhsberg
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
package com.github.mc1arke.sonarqube.plugin.ce.pullrequest.bitbucket.client;

import com.github.mc1arke.sonarqube.plugin.ce.pullrequest.bitbucket.client.model.server.CreateAnnotationsRequest;
import com.github.mc1arke.sonarqube.plugin.ce.pullrequest.bitbucket.client.model.server.CreateReportRequest;
import com.github.mc1arke.sonarqube.plugin.ce.pullrequest.bitbucket.client.model.server.ServerProperties;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import org.sonar.api.ce.ComputeEngineSide;
import org.sonar.api.utils.log.Logger;
import org.sonar.api.utils.log.Loggers;

import java.io.IOException;
import java.util.Optional;

import static java.lang.String.format;

@ComputeEngineSide
public class BitbucketServerClient extends AbstractBitbucketClient {
    private static final Logger LOGGER = Loggers.get(BitbucketServerClient.class);
    private static final String REPORT_KEY = "com.github.mc1arke.sonarqube";
    private static final MediaType APPLICATION_JSON_MEDIA_TYPE = MediaType.get("application/json");

    public ServerProperties getServerProperties() throws IOException {
        Request req = new Request.Builder()
                .get()
                .url(format("%s/rest/api/1.0/application-properties", baseUrl()))
                .build();
        try (Response response = getClient().newCall(req).execute()) {
            validate(response);

            return getObjectMapper().reader().forType(ServerProperties.class)
                    .readValue(Optional.ofNullable(response.body())
                            .orElseThrow(() -> new IllegalStateException("No response body from BitBucket"))
                            .string());
        }
    }

    public void createReport(String project, String repository, String commit, CreateReportRequest request) throws IOException {
        String body = getObjectMapper().writeValueAsString(request);
        Request req = new Request.Builder()
                .put(RequestBody.create(APPLICATION_JSON_MEDIA_TYPE, body))
                .url(format("%s/rest/insights/1.0/projects/%s/repos/%s/commits/%s/reports/%s", baseUrl(), project, repository, commit, REPORT_KEY))
                .build();

        try (Response response = getClient().newCall(req).execute()) {
            validate(response);
        }
    }

    public void createAnnotations(String project, String repository, String commit, CreateAnnotationsRequest request) throws IOException {
        if (request.getAnnotations().isEmpty()) {
            return;
        }
        Request req = new Request.Builder()
                .post(RequestBody.create(APPLICATION_JSON_MEDIA_TYPE, getObjectMapper().writeValueAsString(request)))
                .url(format("%s/rest/insights/1.0/projects/%s/repos/%s/commits/%s/reports/%s/annotations", baseUrl(), project, repository, commit, REPORT_KEY))
                .build();
        try (Response response = getClient().newCall(req).execute()) {
            validate(response);
        }
    }

    public void deleteAnnotations(String project, String repository, String commit) throws IOException {
        Request req = new Request.Builder()
                .delete()
                .url(format("%s/rest/insights/1.0/projects/%s/repos/%s/commits/%s/reports/%s/annotations", baseUrl(), project, repository, commit, REPORT_KEY))
                .build();
        try (Response response = getClient().newCall(req).execute()) {
            validate(response);
        }
    }

    public boolean supportsCodeInsights() {
        try {
            ServerProperties server = getServerProperties();
            LOGGER.debug(format("Your Bitbucket Server installation is version %s", server.getVersion()));
            if (server.hasCodeInsightsApi()) {
                return true;
            } else {
                LOGGER.info("Bitbucket Server version is to old. %s is the minimum version that supports Code Insights",
                        ServerProperties.CODE_INSIGHT_VERSION);
            }
        } catch (IOException e) {
            LOGGER.error("Could not determine Bitbucket Server version", e);
            return false;
        }
        return false;
    }

    private OkHttpClient getClient() {
        return new OkHttpClient.Builder()
                .addInterceptor(chain -> {
                    Request newRequest  = chain.request().newBuilder()
                            .addHeader("Authorization", format("Bearer %s", getToken()))
                            .addHeader("Accept", APPLICATION_JSON_MEDIA_TYPE.toString())
                            .build();
                    return chain.proceed(newRequest);
                })
                .build();
    }
}
