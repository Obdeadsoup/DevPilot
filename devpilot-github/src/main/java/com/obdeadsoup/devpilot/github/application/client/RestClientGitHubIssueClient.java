package com.obdeadsoup.devpilot.github.application.client;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 复用统一 Executor 的 Issues Client；过滤 PR 标记后才映射业务快照。 */
@Component
public class RestClientGitHubIssueClient extends GitHubSnapshotClientSupport implements GitHubIssueClient {
    static final String OPERATION="repository.issues.list",ENDPOINT="/repos/{owner}/{repo}/issues";
    public RestClientGitHubIssueClient(GitHubApiHttpExecutor executor,ObjectMapper mapper){super(executor,mapper);}
    @Override public GitHubPage<GitHubIssue> listIssues(String owner,String repository,Instant since,int perPage,
                                                       String credentialRef,GitHubPageCursor cursor){
        GitHubApiResponse<IssueResponse[]> response;
        if(cursor!=null&&cursor.hasNext())response=executor.getPage(OPERATION,ENDPOINT,cursor,credentialRef,IssueResponse[].class);
        else{Map<String,Object>query=new LinkedHashMap<>();query.put("state","all");query.put("since",since.toString());
            query.put("sort","updated");query.put("direction","asc");query.put("per_page",perPage);
            response=executor.get(OPERATION,ENDPOINT,List.of("repos",owner,repository,"issues"),query,credentialRef,
                    GitHubConditionalRequest.none(),IssueResponse[].class);}
        if(response.body()==null)throw malformed(response,"GitHub API returned issues without body");
        List<GitHubIssue>items=Arrays.stream(response.body()).filter(item->item!=null&&item.pullRequest()==null)
                .map(item->toIssue(item,response)).toList();return new GitHubPage<>(items,response.pageCursor());
    }
    private GitHubIssue toIssue(IssueResponse i,GitHubApiResponse<?> r){if(i.id()==null||i.id()<=0||i.number()==null||i.number()<=0
            ||i.title()==null||i.state()==null||i.htmlUrl()==null)throw malformed(r,"GitHub API issue identity is invalid");
        return new GitHubIssue(i.id(),i.number(),i.title(),i.body(),i.state(),i.stateReason(),
                i.user()==null?null:i.user().id(),i.user()==null?null:i.user().login(),
                json(i.assignees()==null?List.of():i.assignees().stream().map(User::login).toList(),r),
                json(i.labels()==null?List.of():i.labels().stream().map(Label::name).toList(),r),i.htmlUrl(),
                requiredTime(i.createdAt(),r),requiredTime(i.updatedAt(),r),time(i.closedAt(),r));}
    @JsonIgnoreProperties(ignoreUnknown=true)record IssueResponse(Long id,Integer number,String title,String body,String state,
            @JsonProperty("state_reason")String stateReason,User user,List<User>assignees,List<Label>labels,
            @JsonProperty("html_url")String htmlUrl,@JsonProperty("created_at")String createdAt,
            @JsonProperty("updated_at")String updatedAt,@JsonProperty("closed_at")String closedAt,
            @JsonProperty("pull_request")JsonNode pullRequest){}
    @JsonIgnoreProperties(ignoreUnknown=true)record User(Long id,String login){}
    @JsonIgnoreProperties(ignoreUnknown=true)record Label(String name){}
}
