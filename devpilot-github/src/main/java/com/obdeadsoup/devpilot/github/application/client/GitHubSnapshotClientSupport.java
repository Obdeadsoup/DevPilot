package com.obdeadsoup.devpilot.github.application.client;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.List;

abstract class GitHubSnapshotClientSupport {
    protected final GitHubApiHttpExecutor executor;private final ObjectMapper mapper;
    GitHubSnapshotClientSupport(GitHubApiHttpExecutor executor,ObjectMapper mapper){this.executor=executor;this.mapper=mapper;}
    protected Instant requiredTime(String value,GitHubApiResponse<?> response){Instant result=time(value,response);
        if(result==null)throw malformed(response,"GitHub API response is missing a timestamp");return result;}
    protected Instant time(String value,GitHubApiResponse<?> response){if(value==null)return null;
        try{return Instant.parse(value);}catch(DateTimeParseException e){throw malformed(response,"GitHub API timestamp is invalid");}}
    protected String json(List<String> values,GitHubApiResponse<?> response){try{return mapper.writeValueAsString(values);}
        catch(JsonProcessingException e){throw malformed(response,"GitHub API summary is invalid");}}
    protected GitHubApiException malformed(GitHubApiResponse<?> response,String message){return new GitHubApiException(
            GitHubApiFailureType.MALFORMED_RESPONSE,false,null,response.httpStatus(),message,
            response.rateLimit()==null?null:response.rateLimit().requestId(),response.rateLimit());}
}
