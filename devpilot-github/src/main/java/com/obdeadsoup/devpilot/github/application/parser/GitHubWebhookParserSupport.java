package com.obdeadsoup.devpilot.github.application.parser;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.obdeadsoup.devpilot.framework.error.BusinessException;
import com.obdeadsoup.devpilot.github.error.GitHubWebhookErrorCode;
import com.obdeadsoup.devpilot.github.persistence.entity.GitHubDeliveryEntity;
import com.obdeadsoup.devpilot.github.persistence.entity.GitHubRepositoryEntity;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

abstract class GitHubWebhookParserSupport {
    protected final ObjectMapper objectMapper;
    GitHubWebhookParserSupport(ObjectMapper objectMapper){this.objectMapper=objectMapper;}

    protected JsonNode root(GitHubDeliveryEntity delivery,GitHubRepositoryEntity binding){
        try{JsonNode root=objectMapper.readTree(delivery.payloadJson());
            long repositoryId=requiredLong(root.path("repository"),"id");
            if(repositoryId!=binding.githubRepositoryId())throw malformed();return root;
        }catch(JsonProcessingException exception){throw malformed();}
    }
    protected long requiredLong(JsonNode node,String field){JsonNode value=node.path(field);
        if(!value.canConvertToLong()||value.longValue()<=0)throw malformed();return value.longValue();}
    protected int requiredInt(JsonNode node,String field){JsonNode value=node.path(field);
        if(!value.canConvertToInt()||value.intValue()<=0)throw malformed();return value.intValue();}
    protected String requiredText(JsonNode node,String field){String value=text(node,field);
        if(value==null||value.isBlank())throw malformed();return value;}
    protected String text(JsonNode node,String field){JsonNode value=node.path(field);return value.isTextual()?value.textValue():null;}
    protected LocalDateTime requiredTime(JsonNode node,String field){String value=requiredText(node,field);
        try{return OffsetDateTime.parse(value).withOffsetSameInstant(ZoneOffset.UTC).toLocalDateTime();}
        catch(RuntimeException exception){throw malformed();}}
    protected LocalDateTime time(JsonNode node,String field){String value=text(node,field);if(value==null)return null;
        try{return OffsetDateTime.parse(value).withOffsetSameInstant(ZoneOffset.UTC).toLocalDateTime();}
        catch(RuntimeException exception){throw malformed();}}
    protected String names(JsonNode array,String preferredField){
        if(!array.isArray())return "[]";ArrayNode result=objectMapper.createArrayNode();
        for(JsonNode item:array){String value=text(item,preferredField);if(value==null)value=text(item,"login");
            if(value!=null)result.add(value);}return result.toString();}
    protected Long nullableId(JsonNode node){JsonNode value=node.path("id");return value.canConvertToLong()&&value.longValue()>0?value.longValue():null;}
    protected BusinessException malformed(){return new BusinessException(GitHubWebhookErrorCode.MALFORMED_PAYLOAD);}
}
