package com.couchbase.versions

import groovy.transform.CompileStatic
import groovy.transform.Memoized

@CompileStatic
class PythonVersions {
    private final static String REPO = "couchbase/couchbase-python-client"
    private final static String BRANCH = "master"

    @Memoized
    private static String getLatestSha() {
        return GithubVersions.getLatestShaWithDatetime(REPO, BRANCH)
    }

    @Memoized
    static Set<ImplementationVersion> getAllReleases() {
        return GithubVersions.getAllReleases(REPO)
    }

    @Memoized
    static SnapshotVersion getLatestSnapshot() {
        def allReleases = GithubVersions.getRecentReleases(REPO)
        def highest = ImplementationVersion.highest(allReleases)
        String snapshotVersion = formatSnapshotVersion(highest, getLatestSha())
        ImplementationVersion out = ImplementationVersion.from(snapshotVersion)
        String sha = (out.snapshot != null && out.snapshot.contains('+')) ? out.snapshot.split("\\+").last() : null
        return new SnapshotVersion(out, sha)
    }

    static String formatSnapshotVersion(ImplementationVersion version, String sha) {
        return Versions.appendPreReleaseIdentifierToVersion(version.toString(), sha)
    }
}
