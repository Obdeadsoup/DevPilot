package com.obdeadsoup.devpilot.github.application.secret;

import java.util.Optional;

public interface WebhookSecretResolver {

    Optional<String> resolve(String credentialReference);
}
