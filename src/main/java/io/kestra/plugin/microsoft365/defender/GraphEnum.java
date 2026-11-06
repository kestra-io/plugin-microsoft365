package io.kestra.plugin.microsoft365.defender;

import com.microsoft.kiota.serialization.ValuedEnum;

/**
 * A Kestra-side enum of the Defender tasks that maps to a Microsoft Graph SDK enum.
 */
interface GraphEnum {
    ValuedEnum toGraph();
}
