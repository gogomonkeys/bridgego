package com.bridge.quartercore;
/** Deliberately unprovisioned. A future separately approved reviewed build may return its
 * fixed contract here. Environment settings, Sheet values and HTTP cannot provision it. */
final class NativeRestartCompiledRelease {
 private NativeRestartCompiledRelease(){}
 static NativeRestartContract current(){return null;}
 static NativeRestartContract require(){var release=current();if(release==null)throw new IllegalStateException("Native restart release is not provisioned");return release;}
}
