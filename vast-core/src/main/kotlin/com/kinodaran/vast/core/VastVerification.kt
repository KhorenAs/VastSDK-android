package com.kinodaran.vast.core

/**
 * One `<Verification>` from `<AdVerifications>` (§3.16): a measurement vendor
 * asking to observe this ad.
 *
 * The SDK parses these and hands them over; it never executes them. Running
 * verification code means the IAB Open Measurement SDK, which is licensed
 * separately and lives outside this library — so what belongs here is the
 * description of what was asked for, and enough detail to report honestly when
 * nothing runs it.
 */
public data class VastVerification(
    /** `<Verification vendor>`. Absent on responses that predate the attribute. */
    val vendor: String?,
    val resources: List<Resource>,
    /** `<VerificationParameters>` — opaque to everyone but the vendor. */
    val parameters: String? = null,
    /**
     * `<Tracking event="verificationNotExecuted">`. Owed when the vendor's code
     * does not run, so the vendor can tell "unmeasured" from "unserved".
     */
    val notExecutedTrackers: List<String> = emptyList(),
) {

    /**
     * The resource an Open Measurement integration can actually use.
     *
     * `null` means nothing here is executable by an OMID host — an
     * `<ExecutableResource>`, or a JavaScript resource for some other framework.
     * That distinction is what separates [NotExecutedReason.RESOURCE_NOT_SUPPORTED]
     * from [NotExecutedReason.NOT_EXECUTED] when reporting.
     */
    val omidResource: Resource? get() = resources.firstOrNull { it.kind == Resource.Kind.JAVA_SCRIPT && it.isOmid }

    /** One `<JavaScriptResource>` or `<ExecutableResource>`. */
    public data class Resource(
        val kind: Kind,
        val url: String,
        /** `omid` for Open Measurement. Other values exist and are not ours. */
        val apiFramework: String? = null,
        /**
         * `<JavaScriptResource browserOptional>`: whether the script can run
         * outside a browser context. Defaults to false per §3.16.
         */
        val browserOptional: Boolean = false,
        /** `<ExecutableResource type>`, e.g. a platform identifier. */
        val type: String? = null,
    ) {
        /** Ad servers are inconsistent about the case of `omid`. */
        val isOmid: Boolean get() = apiFramework?.lowercase() == "omid"

        public enum class Kind {
            JAVA_SCRIPT,

            /**
             * Native vendor code. Nothing in this SDK can run one; it is modelled
             * so the reason reported back is the true one.
             */
            EXECUTABLE,
        }
    }

    /**
     * Why a vendor's code did not run, as the values §3.16 defines for the
     * `verificationNotExecuted` tracker's `[REASON]` macro.
     */
    public enum class NotExecutedReason(public val code: Int) {
        /**
         * No resource this host could ever execute — executable-only, or a
         * JavaScript resource for a framework other than OMID.
         */
        RESOURCE_NOT_SUPPORTED(1),

        /**
         * A usable resource that failed to load or timed out. Only whoever tried
         * to load it knows this, so the SDK never reports it on its own.
         */
        RESOURCE_LOAD_ERROR(2),

        /**
         * A usable resource that nothing was asked to run — no measurement
         * integration is configured.
         */
        NOT_EXECUTED(3),
    }
}
