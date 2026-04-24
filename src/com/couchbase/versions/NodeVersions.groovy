package com.couchbase.versions

import com.couchbase.tools.network.NetworkUtil
import groovy.json.JsonSlurper
import groovy.transform.Memoized

class NodeVersions {
    private final static String REPO = "couchbase/couchnode"
    private final static String BRANCH = "master"

    @Memoized
    private static String getLatestSha() {
        return GithubVersions.getLatestSha(REPO, BRANCH)
    }

    @Memoized
    static SnapshotVersion getLatestSnapshot() {
        ImplementationVersion latestRelease = GithubVersions.getLatestRelease(REPO)
        String sha = getLatestSha()
        ImplementationVersion snapshotVersion = ImplementationVersion.from("${latestRelease.major}.${latestRelease.minor}.${latestRelease.patch}-${sha}")
        return new SnapshotVersion(snapshotVersion, sha)
    }

    @Memoized
    static Set<ImplementationVersion> getAllReleases() {
        // Note this needs to be refactored to use GithibVersions, but will need to handle
        // the `version.substring(1, version.length()))` logic
        def out = new HashSet<ImplementationVersion>()

        String url = "https://api.github.com/repos/couchbase/couchnode/tags"
        String content = NetworkUtil.read(url)
        def parser = new JsonSlurper()
        def json = parser.parseText(content)

        for (doc in json) {
            String version = doc.name
            try {
                out.add(ImplementationVersion.from(version.substring(1, version.length())))
            }
            catch (err) {
                System.err.println("Failed to add node version ${doc}")
            }
        }

        return out
    }
}
