package io.kestra.plugin.microsoft365.defender;

import com.microsoft.graph.models.security.AlertDetermination;

public enum Determination implements GraphEnum {
    UNKNOWN(AlertDetermination.Unknown),
    APT(AlertDetermination.Apt),
    MALWARE(AlertDetermination.Malware),
    SECURITY_PERSONNEL(AlertDetermination.SecurityPersonnel),
    SECURITY_TESTING(AlertDetermination.SecurityTesting),
    UNWANTED_SOFTWARE(AlertDetermination.UnwantedSoftware),
    OTHER(AlertDetermination.Other),
    MULTI_STAGED_ATTACK(AlertDetermination.MultiStagedAttack),
    COMPROMISED_ACCOUNT(AlertDetermination.CompromisedAccount),
    PHISHING(AlertDetermination.Phishing),
    MALICIOUS_USER_ACTIVITY(AlertDetermination.MaliciousUserActivity),
    NOT_MALICIOUS(AlertDetermination.NotMalicious),
    NOT_ENOUGH_DATA_TO_VALIDATE(AlertDetermination.NotEnoughDataToValidate),
    CONFIRMED_ACTIVITY(AlertDetermination.ConfirmedActivity),
    LINE_OF_BUSINESS_APPLICATION(AlertDetermination.LineOfBusinessApplication);

    private final AlertDetermination graphValue;

    Determination(AlertDetermination graphValue) {
        this.graphValue = graphValue;
    }

    @Override
    public AlertDetermination toGraph() {
        return graphValue;
    }
}
