package com.obdeadsoup.devpilot.project.api;

import com.obdeadsoup.devpilot.project.api.dto.CreateProjectRequest;
import com.obdeadsoup.devpilot.project.api.dto.CreateWorkspaceRequest;
import com.obdeadsoup.devpilot.project.api.dto.ProjectResponse;
import com.obdeadsoup.devpilot.project.api.dto.UpdateProjectRequest;
import com.obdeadsoup.devpilot.project.api.dto.UpdateWorkspaceRequest;
import com.obdeadsoup.devpilot.project.api.dto.VersionRequest;
import com.obdeadsoup.devpilot.project.api.dto.WorkspaceResponse;
import org.junit.jupiter.api.Test;

import java.lang.reflect.RecordComponent;
import java.util.Arrays;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

class LifecycleApiContractTest {

    @Test
    void responseDtosDoNotExposePersistenceInternals() {
        assertThat(componentNames(WorkspaceResponse.class))
                .contains("ownerUserId")
                .doesNotContain("deleted", "createdBy", "activeProjectKey");
        assertThat(componentNames(ProjectResponse.class))
                .doesNotContain("ownerUserId", "createdBy", "deleted", "activeProjectKey");
    }

    @Test
    void requestDtosNeverAcceptCurrentUserOrOwnershipFields() {
        Stream.of(
                        CreateWorkspaceRequest.class,
                        UpdateWorkspaceRequest.class,
                        CreateProjectRequest.class,
                        UpdateProjectRequest.class,
                        VersionRequest.class
                )
                .map(this::componentNames)
                .forEach(names -> assertThat(names)
                        .doesNotContain("currentUserId", "ownerUserId", "createdBy", "userId"));
    }

    @Test
    void controllersHaveNoCurrentUserIdParameter() {
        Stream.of(WorkspaceController.class, ProjectController.class)
                .flatMap(type -> Arrays.stream(type.getDeclaredMethods()))
                .flatMap(method -> Arrays.stream(method.getParameters()))
                .forEach(parameter -> assertThat(parameter.getName()).isNotEqualTo("currentUserId"));
    }

    private String[] componentNames(Class<?> recordType) {
        return Arrays.stream(recordType.getRecordComponents())
                .map(RecordComponent::getName)
                .toArray(String[]::new);
    }
}
