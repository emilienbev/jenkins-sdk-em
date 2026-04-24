package com.couchbase.versions

import groovy.transform.CompileStatic
import groovy.transform.Memoized


@CompileStatic
class RubyVersions {
    private final static String REPO = "couchbase/couchbase-ruby-client"
    private final static String BRANCH = "main"

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
        ImplementationVersion latestRelease = GithubVersions.getLatestRelease(REPO)
        String snapshotVersion = formatSnapshotVersion(latestRelease, getLatestSha())
        ImplementationVersion out = ImplementationVersion.from(snapshotVersion)
        String sha = (out.snapshot != null && out.snapshot.contains('+')) ? out.snapshot.split("\\+").last() : null
        return new SnapshotVersion(out, sha)
    }

    static String formatSnapshotVersion(ImplementationVersion version, String sha) {
        return Versions.appendPreReleaseIdentifierToVersion(version.toString(), sha)
    }
}
