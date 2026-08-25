package com.obdeadsoup.devpilot.agent.infrastructure.grpc;

import io.grpc.ManagedChannel;
import org.junit.jupiter.api.Test;

import java.util.concurrent.TimeUnit;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AgentGrpcChannelTest {

    @Test
    void gracefullyClosesLongLivedManagedChannel() throws Exception {
        ManagedChannel managedChannel = mock(ManagedChannel.class);
        when(managedChannel.shutdown()).thenReturn(managedChannel);
        when(managedChannel.awaitTermination(5, TimeUnit.SECONDS)).thenReturn(true);

        new AgentGrpcChannel(managedChannel).close();

        verify(managedChannel).shutdown();
        verify(managedChannel).awaitTermination(5, TimeUnit.SECONDS);
        verify(managedChannel, never()).shutdownNow();
    }

    @Test
    void forcesShutdownAfterGracePeriodExpires() throws Exception {
        ManagedChannel managedChannel = mock(ManagedChannel.class);
        when(managedChannel.shutdown()).thenReturn(managedChannel);
        when(managedChannel.awaitTermination(5, TimeUnit.SECONDS)).thenReturn(false);
        when(managedChannel.shutdownNow()).thenReturn(managedChannel);

        new AgentGrpcChannel(managedChannel).close();

        verify(managedChannel).shutdownNow();
    }
}
