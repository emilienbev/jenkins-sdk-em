package com.couchbase.versions

import groovy.transform.CompileStatic
import groovy.transform.Memoized

@CompileStatic
class RustVersions {
    private final static String REPO = "couchbaselabs/couchbase-rs"
    private final static String BRANCH = "main"
    // Rust release tags use a "v" prefix, which must be removed for proper version parsing
    private final static Closure<String> STRIP_V_PREFIX = { String name ->
        name.startsWith("v") ? name.substring(1) : name
    }

    @Memoized
    static Set<ImplementationVersion> getAllReleases() {
        var allReleases = GithubVersions.getAllReleases(REPO, STRIP_V_PREFIX).findAll()
        // We want to exclude non-native Rust SDK releases, which are all pre-1.0.0
        return allReleases.findAll { version -> version.major != 0 }
    }

    @Memoized
    static SnapshotVersion getLatestSnapshot() {
        def snapshot = getSnapshot(BRANCH, true)
        String sha = (snapshot.snapshot != null && snapshot.snapshot.contains('+')) ? snapshot.snapshot.split("\\+").last() : null
        return new SnapshotVersion(snapshot, sha)
    }

    static ImplementationVersion getSnapshot(String shaOrBranch, boolean nextReleaseIsDotMinor) {
        def attrs = GithubVersions.getSnapshotAttributes(REPO, shaOrBranch, STRIP_V_PREFIX)
        return attrs.toImplementationVersion(nextReleaseIsDotMinor)
    }
}
