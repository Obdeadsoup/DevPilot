package com.obdeadsoup.devpilot.audit.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.obdeadsoup.devpilot.audit.domain.*;
import com.obdeadsoup.devpilot.audit.persistence.mapper.AuditLogMapper;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.Map;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.mock;

class AuditMetadataPolicyTest {
    @Test void rejectsPayloadAndTokenMetadataKeys(){
        var service=new AuditApplicationService(mock(AuditLogMapper.class),new ObjectMapper());
        var command=new AuditRecordCommand(AuditActorType.USER,1L,1L,1L,AuditActionType.OUTBOX_REPLAY_CREATED,
                AuditResourceType.OUTBOX_EVENT,"1",AuditResult.SUCCESS,"valid replay reason",null,null,null,
                Map.of("payload","secret"), LocalDateTime.now());
        assertThatThrownBy(()->service.append(command)).isInstanceOf(IllegalArgumentException.class);
    }
}
