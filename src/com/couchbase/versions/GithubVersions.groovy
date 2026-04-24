package com.couchbase.versions

import com.couchbase.tools.network.NetworkUtil
import groovy.json.JsonSlurper
import java.util.stream.Collectors

class GithubSnapshotAttributes {
    public String sha
    public ImplementationVersion nearestRelease
    public int commitsSinceRelease

    GithubSnapshotAttributes(String sha, ImplementationVersion nearestRelease, int commitsSinceRelease) {
        this.sha = sha
        this.nearestRelease = nearestRelease
        this.commitsSinceRelease = commitsSinceRelease
    }

    ImplementationVersion toImplementationVersion(boolean maybeIncrementMinor) {
        int major = this.nearestRelease.major
        int minor = this.nearestRelease.minor
        int patch = this.nearestRelease.patch
        String snapshot
        if (this.commitsSinceRelease > 0) {
            if (this.nearestRelease.snapshot == null) {
                // This commit is _after_ a non-RC release - increment the patch or minor
                if (maybeIncrementMinor) {
                    patch = 0
                    minor++
                } else {
                    patch += 1
                }
                snapshot = "-${commitsSinceRelease}+${sha.substring(0, 7)}"
            } else {
                snapshot = "${nearestRelease.snapshot}.${commitsSinceRelease}+${sha.substring(0, 7)}"
            }
        } else {
            snapshot = this.nearestRelease.snapshot
        }
        return new ImplementationVersion(major, minor, patch, snapshot)
    }

    String toString() {
        return "sha=${this.sha}, nearestRelease=${this.nearestRelease}, commitsSinceRelease=${commitsSinceRelease}"
    }
}

class GithubVersions {
    // Note this only works for repos that do GitHub releases
    static ImplementationVersion getLatestRelease(String repo) {
        def json = NetworkUtil.readJson("https://api.github.com/repos/${repo}/releases/latest")
        String tagName = json.tag_name
        if (tagName != null && !tagName.trim().isEmpty()) {
            return ImplementationVersion.from(normalizeVersionTag(tagName, null))
        }
        return null
    }

    @Deprecated // SDKs should use getLatestShaWithDatetime so that results are chronologically sorted
    static String getLatestSha(String repo, String branch) {
        // Test with:
        // curl "https://api.github.com/repos/couchbase-net-client/commits/master"
        def json = NetworkUtil.readJson("https://api.github.com/repos/${repo}/commits/${branch}")
        String sha = json.sha
        return sha.substring(0, 7)
    }

    /**
     * Makes a more useful snapshot version by putting the datetime in, so it appears in chronological order in graphs etc.
     *
     * Note this has some limitations.  A snapshot for 3.4.5 is actually _after_ 3.4.5 is released, rather than the
     * JVM definition of a SNAPSHOT build, which would come before 3.4.5 releases.  The latter is a more useful
     * definition, but is hard to achieve.
     */
    static String getLatestShaWithDatetime(String repo, String branch) {
        // Test with:
        // curl "https://api.github.com/repos/couchbase-net-client/commits/master"
        def json = NetworkUtil.readJson("https://api.github.com/repos/${repo}/commits/${branch}")
        String sha = json.sha
        String commitDate = json.commit.committer.date
        String[] parts = commitDate.split("T")
        String date = parts[0].replaceAll("[^0-9]", "")
        String time = parts[1].replaceAll("[^0-9]", "")
        // Why 7 characters here but 6 characters in getLatestSha?  Because it was in the code that's being refactored
        // here.  It's probably not strictly necessary, but keeping it so the SDK Performance results remain consistent.
        // Use '+' to append the SHA, to treat it as build metadata that's ignored in semver sorting.
        return date + "." + time + "+" + sha.substring(0, 7)
    }

    static GithubSnapshotAttributes getSnapshotAttributes(String repo, String shaOrBranch, Closure<String> versionTagNormalizer = null) {
        def fastPath = getSnapshotAttributesFastPath(repo, shaOrBranch, versionTagNormalizer)
        if (fastPath != null) {
            return fastPath
        }

        var tags = new HashMap<String, ImplementationVersion>()
        for (var it : getAllReleasesWithCommitSha(repo, versionTagNormalizer)) {
            if (!tags.containsKey(it.getV1()) || it.getV2() > tags.get(it.getV1())) {
                // If there are multiple tags on the same commit, keep the one representing the highest version
                tags.put(it.getV1(), it.getV2())
            }
        }

        def url = "https://api.github.com/repos/${repo}/commits?sha=${shaOrBranch}"
        int commitsSinceTag = 0
        String snapshotSha = null

        while (url != null) {
            println url

            def commitsConnection = NetworkUtil.readRaw(url)
            def commitsParser = new JsonSlurper()
            def commitsJson = commitsParser.parseText(commitsConnection.getInputStream().getText())

            for (commit in commitsJson) {
                if (snapshotSha == null) {
                    snapshotSha = commit.sha.substring(0, 7)
                }

                if (tags.containsKey(commit.sha)) {
                    // This is the first commit we found that's been tagged as a release
                    return new GithubSnapshotAttributes(snapshotSha, tags.get(commit.sha), commitsSinceTag)
                }
                commitsSinceTag++
            }
            url = parseLinkHeaderForNext(commitsConnection)
        }
        throw new RuntimeException("Could not find the nearest tag for ${shaOrBranch} in ${repo}")
    }

    private static GithubSnapshotAttributes getSnapshotAttributesFastPath(String repo, String shaOrBranch, Closure<String> versionTagNormalizer = null) {
        try {
            def releaseJson = NetworkUtil.readJson("https://api.github.com/repos/${repo}/releases/latest")
            String tagName = releaseJson.tag_name
            if (tagName == null || tagName.trim().isEmpty()) {
                return null
            }

            ImplementationVersion latestRelease = ImplementationVersion.from(normalizeVersionTag(tagName, versionTagNormalizer))
            def compareJson = NetworkUtil.readJson("https://api.github.com/repos/${repo}/compare/${tagName}...${shaOrBranch}")
            String status = compareJson.status

            // If the latest release tag isn't an ancestor of the target, use the slower fallback path.
            if ("behind".equalsIgnoreCase(status) || "diverged".equalsIgnoreCase(status)) {
                return null
            }

            int commitsSinceRelease = (compareJson.ahead_by ?: 0) as int
            def headJson = NetworkUtil.readJson("https://api.github.com/repos/${repo}/commits/${shaOrBranch}")
            String snapshotSha = headJson.sha.substring(0, 7)
            return new GithubSnapshotAttributes(snapshotSha, latestRelease, commitsSinceRelease)
        }
        catch (Throwable ignored) {
            return null
        }
    }

    static GithubSnapshotAttributes getSnapshotAttributesUsingReferenceCommit(
            String repo,
            String shaOrBranch,
            String referenceSha,
            ImplementationVersion referenceVersion) {

        int commitsSinceReference = 0
        String snapshotSha = null

        def url = "https://api.github.com/repos/${repo}/commits?sha=${shaOrBranch}"

        while (url != null) {
            def commitsConnection = NetworkUtil.readRaw(url)
            def commitsParser = new JsonSlurper()
            def commitsJson = commitsParser.parseText(commitsConnection.getInputStream().getText())

            for (commit in commitsJson) {
                if (snapshotSha == null) {
                    snapshotSha = commit.sha.substring(0, 7)
                }

                if (commit.sha == referenceSha) {
                    return new GithubSnapshotAttributes(snapshotSha, referenceVersion, commitsSinceReference)
                }
                commitsSinceReference++
            }
            url = parseLinkHeaderForNext(commitsConnection)
        }
        throw new RuntimeException("Could not find reference commit ${referenceSha} from ${shaOrBranch} in ${repo}")
    }

    static Set<ImplementationVersion> getAllReleases(String repo, Closure<String> versionTagNormalizer = null) {
        return getAllReleasesWithCommitSha(repo, versionTagNormalizer).stream().map(Tuple2::getV2).collect(Collectors.toSet())
    }

    // getAllReleases is contributing to Github API 403s.  This more optimised version fetches only the most recent
    // page of tags, which is suitable for some use-cases such as finding the latest snapshot.
    static Set<ImplementationVersion> getRecentReleases(String repo, Closure<String> versionTagNormalizer = null) {
        return getAllReleasesWithCommitSha(repo, versionTagNormalizer, true).stream().map(Tuple2::getV2).collect(Collectors.toSet())
    }

    private static Set<Tuple2<String, ImplementationVersion>> getAllReleasesWithCommitSha(String repo,
                                                                                          Closure<String> versionTagNormalizer = null,
                                                                                          boolean firstPageOnly = false) {
        def out = new HashSet<Tuple2<String, ImplementationVersion>>();
        // Test with:
        // curl "https://api.github.com/repos/couchbase-net-client/tags"
        def baseUrl = "https://api.github.com/repos/${repo}/tags"
        def nextUrl = baseUrl

        while (nextUrl != null) {
            println nextUrl
            def connection = NetworkUtil.readRaw(nextUrl)
            def parser = new JsonSlurper()
            def json = parser.parseText(connection.getInputStream().getText())
            nextUrl = parseLinkHeaderForNext(connection)

            for (doc in json) {
                try {
                    def name = doc.name
                    if (versionTagNormalizer != null) {
                        name = versionTagNormalizer.call(name)
                    }
                    out.add(new Tuple2<String, ImplementationVersion>(doc.commit.sha, ImplementationVersion.from(name)))
                } catch (e) {
                    System.err.println("Failed to add ${repo} version ${doc}: ${e}")
                }
            }

            if (firstPageOnly) {
                break
            }
        }

        return out
    }

    private static String parseLinkHeaderForNext(URLConnection connection) {
        // <https://api.github.com/repositories/2071017/tags?page=2>; rel="next", <https://api.github.com/repositories/2071017/tags?page=7>; rel="last"
        try {
            if (connection.getHeaderFields() == null || !connection.getHeaderFields().containsKey("Link")) {
                return null
            }
            def linkHeaders = connection.getHeaderFields().get("Link").get(0)
            if (!linkHeaders.contains("rel=\"next\"")) {
                return null
            }
            linkHeaders.split(",")
                    .find { it.contains("rel=\"next\"") }
                    .split(";")[0]
                    .replace('<', '')
                    .replace('>', '')
        }
        catch (RuntimeException err) {
            err.printStackTrace()
            throw new RuntimeException("Unable to parse headers from ${connection.getHeaderFields()}")
        }
    }

    private static String normalizeVersionTag(String tagName, Closure<String> versionTagNormalizer = null) {
        String normalizedTag = versionTagNormalizer != null ? versionTagNormalizer.call(tagName) : tagName
        if (normalizedTag != null && normalizedTag.toLowerCase().startsWith("v")) {
            normalizedTag = normalizedTag.substring(1)
        }
        return normalizedTag
    }
}
