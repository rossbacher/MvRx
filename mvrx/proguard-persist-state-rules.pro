# ------------------------
#
#  Mavericks @PersistState Config
#
# -----------------------
#
# persistMavericksState() calls componentN() for each constructor parameter annotated with @PersistState, and
# restorePersistedMavericksState() calls the static copy$default() method to rebuild the state with the restored values.
#
# Only states with @PersistState parameters need these members, but a keep rule cannot match parameter annotations, so this
# rule keeps them on every state. That is a lot of code in an app with many states. An app that can list its states with
# @PersistState parameters can replace this rule: ignore the Mavericks keep rules (AGP: optimization.keepRules.ignoreFrom),
# copy proguard-rules.pro, and keep these members only on those states, for example:
#
# -keepclassmembers class com.example.SearchState {
#     synthetic *** copy$default(...);
#     public *** component*();
# }
-keepclassmembers class ** implements com.airbnb.mvrx.MavericksState {
    synthetic *** copy$default(...);
    public *** component*();
}
