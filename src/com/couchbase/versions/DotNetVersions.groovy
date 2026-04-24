package com.couchbase.versions

import groovy.transform.CompileStatic
import groovy.transform.Memoized


@CompileStatic
class DotNetVersions {
    private final static String REPO = "couchbase/couchbase-net-client"
    private final static String BRANCH = "master"

    @Memoized
    static SnapshotVersion getLatestSnapshot() {
        def allReleases = GithubVersions.getRecentReleases(REPO)
        def highest = ImplementationVersion.highest(allReleases)
        String sha = GithubVersions.getLatestSha(REPO, BRANCH)
        ImplementationVersion snapshotVersion = ImplementationVersion.from("${highest.major}.${highest.minor}.${highest.patch}-${sha}")
        return new SnapshotVersion(snapshotVersion, sha)
    }

    @Memoized
    static Set<ImplementationVersion> getAllReleases() {
        var allVersions = GithubVersions.getAllReleases(REPO)
        var skipVersions = [
                new ImplementationVersion(3, 4, 10, "-rc1"),
                new ImplementationVersion(3, 4, 5, "-rc2")
        ]
        return allVersions.findAll {it -> !skipVersions.contains(it) }
    }
}
