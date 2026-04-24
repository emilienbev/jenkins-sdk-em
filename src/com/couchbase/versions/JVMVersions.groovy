package com.couchbase.versions

import com.couchbase.tools.network.NetworkUtil
import groovy.transform.Memoized


class JVMVersions {
    private static final String SNAPSHOT_REPO_BASE = "https://central.sonatype.com/repository/maven-snapshots"

    @Memoized
    static ImplementationVersion getLatestSnapshotBuild(String client) {
        return fetchSnapshot(client)
    }

    private static ImplementationVersion fetchSnapshot(String client) {
        def snapshots = NetworkUtil.readXml("${SNAPSHOT_REPO_BASE}/com/couchbase/client/${client}/maven-metadata.xml")

        def versionNodes = snapshots.versioning.versions.version
        if (versionNodes == null || versionNodes.size() == 0) {
            throw new RuntimeException("No snapshot versions found for ${client} at ${SNAPSHOT_REPO_BASE}")
        }

        // "latest" doesn't look up to date so assuming list will always be time-ordered
        def lastSnapshot = versionNodes[versionNodes.size() - 1].text()

        def artifactXml = NetworkUtil.readXml("${SNAPSHOT_REPO_BASE}/com/couchbase/client/${client}/${lastSnapshot}/maven-metadata.xml")

        // "20220715.074746-6"
        def timestamp = artifactXml.versioning.snapshot.timestamp.text()
        def builderNumber = artifactXml.versioning.snapshot.buildNumber.text()
        if (!timestamp || !builderNumber) {
            throw new RuntimeException("Snapshot metadata missing timestamp/buildNumber for ${client} ${lastSnapshot}")
        }
        def version = ImplementationVersion.from(lastSnapshot)
        return ImplementationVersion.from("${version.major}.${version.minor}.${version.patch}-${timestamp}-${builderNumber}")
    }

    @Memoized
    static Set<ImplementationVersion> getAllJVMReleases(String client) {
        def out = new HashSet<ImplementationVersion>()

        String url = "https://repo1.maven.org/maven2/com/couchbase/client/${client}/maven-metadata.xml"
        def xml = NetworkUtil.readXml(url)

        // Filter out 2.X SDKs
        def minJavaVersion = ImplementationVersion.from("3.0.0")
        boolean isJava = client.toLowerCase().startsWith("java")

        xml.versioning.versions.version.each { doc ->
            String version = doc.text()
            try {
                def v = ImplementationVersion.from(version)
                
                if (!isJava || !v.isBelow(minJavaVersion)) {
                    out.add(v)
                }
            }
            catch (err) {
                System.err.println("Failed to add version ${client} ${version}")
            }
        }

        return out
    }
}
