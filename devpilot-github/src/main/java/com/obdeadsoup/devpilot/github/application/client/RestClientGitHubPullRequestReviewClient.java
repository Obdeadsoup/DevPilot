package com.obdeadsoup.devpilot.github.application.client;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

/** Reviews API Client；仅由有界 PR 候选调用并沿可信 Link Cursor 分页。 */
@Component
public class RestClientGitHubPullRequestReviewClient extends GitHubSnapshotClientSupport implements GitHubPullRequestReviewClient{
    static final String OPERATION="repository.pull_request_reviews.list",ENDPOINT="/repos/{owner}/{repo}/pulls/{number}/reviews";
    public RestClientGitHubPullRequestReviewClient(GitHubApiHttpExecutor executor,ObjectMapper mapper){super(executor,mapper);}
    @Override public GitHubPage<GitHubPullRequestReview> listReviews(String owner,String repository,int number,int perPage,
                                                                    String credentialRef,GitHubPageCursor cursor){
        GitHubApiResponse<ReviewResponse[]>r;if(cursor!=null&&cursor.hasNext())r=executor.getPage(OPERATION,ENDPOINT,cursor,credentialRef,ReviewResponse[].class);
        else r=executor.get(OPERATION,ENDPOINT,List.of("repos",owner,repository,"pulls",String.valueOf(number),"reviews"),
                Map.of("per_page",perPage),credentialRef,GitHubConditionalRequest.none(),ReviewResponse[].class);
        if(r.body()==null)throw malformed(r,"GitHub API returned reviews without body");
        return new GitHubPage<>(Arrays.stream(r.body()).map(item->toReview(item,r)).toList(),r.pageCursor());}
    private GitHubPullRequestReview toReview(ReviewResponse v,GitHubApiResponse<?>r){if(v==null||v.id()==null||v.id()<=0||v.state()==null
            ||v.commitId()==null||v.htmlUrl()==null)throw malformed(r,"GitHub API review identity is invalid");
        var submitted=requiredTime(v.submittedAt(),r);var updated=time(v.updatedAt(),r);if(updated==null)updated=submitted;
        return new GitHubPullRequestReview(v.id(),v.user()==null?null:v.user().id(),v.user()==null?null:v.user().login(),
                v.state(),v.body(),v.commitId(),v.htmlUrl(),submitted,updated);}
    @JsonIgnoreProperties(ignoreUnknown=true)record ReviewResponse(Long id,User user,String state,String body,
            @JsonProperty("commit_id")String commitId,@JsonProperty("html_url")String htmlUrl,
            @JsonProperty("submitted_at")String submittedAt,@JsonProperty("updated_at")String updatedAt){}
    @JsonIgnoreProperties(ignoreUnknown=true)record User(Long id,String login){}
}
