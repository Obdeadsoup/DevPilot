package com.obdeadsoup.devpilot.agent.application.proposal;

import com.obdeadsoup.devpilot.agent.application.tool.AgentToolErrorKind;
import com.obdeadsoup.devpilot.agent.application.tool.AgentToolException;
import com.obdeadsoup.devpilot.task.application.command.CreateTaskCommand;
import com.obdeadsoup.devpilot.task.domain.TaskPriority;

import java.time.LocalDateTime;
import java.time.format.DateTimeParseException;
import java.util.Map;
import java.util.Set;

/** task.create 的唯一受信任规范化器；Proposal 与最终 TaskService 使用同一固化值。 */
public record TaskCreateProposalPayload(
        String title, String description, TaskPriority priority, Long assigneeUserId, LocalDateTime dueAt) {
    private static final Set<String> FIELDS = Set.of(
            "title", "description", "priority", "assigneeUserId", "dueAt");

    public static TaskCreateProposalPayload from(Map<String, Object> arguments) {
        if (arguments == null || !FIELDS.containsAll(arguments.keySet())) invalid();
        Object rawTitle = arguments.get("title");
        if (!(rawTitle instanceof String)) invalid();
        String title = ((String) rawTitle).strip().replaceAll("\\s+", " ");
        if (title.isEmpty() || title.length() > 255) invalid();
        String description = optionalString(arguments.get("description"), 10_000);
        TaskPriority priority;
        try {
            priority = arguments.get("priority") == null
                    ? TaskPriority.MEDIUM : TaskPriority.valueOf((String) arguments.get("priority"));
        } catch (RuntimeException exception) {
            throw new AgentToolException(AgentToolErrorKind.INVALID_ARGUMENT);
        }
        Long assignee = positiveLong(arguments.get("assigneeUserId"));
        LocalDateTime dueAt = null;
        if (arguments.get("dueAt") != null) {
            if (!(arguments.get("dueAt") instanceof String)) invalid();
            try { dueAt = LocalDateTime.parse((String) arguments.get("dueAt")); }
            catch (DateTimeParseException exception) { invalid(); }
        }
        return new TaskCreateProposalPayload(title, description, priority, assignee, dueAt);
    }

    public CreateTaskCommand toCommand() {
        return new CreateTaskCommand(title, description, priority, assigneeUserId, dueAt);
    }

    private static String optionalString(Object value, int max) {
        if (value == null) return null;
        if (!(value instanceof String) || ((String) value).length() > max) invalid();
        return ((String) value).strip();
    }

    private static Long positiveLong(Object value) {
        if (value == null) return null;
        if (!(value instanceof Number)) invalid();
        double raw = ((Number) value).doubleValue();
        long result = ((Number) value).longValue();
        if (!Double.isFinite(raw) || raw != result || result < 1) invalid();
        return result;
    }

    private static void invalid() {
        throw new AgentToolException(AgentToolErrorKind.INVALID_ARGUMENT);
    }
}
