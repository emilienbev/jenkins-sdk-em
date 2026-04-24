package com.couchbase.versions

import groovy.transform.CompileStatic

@CompileStatic
class SnapshotVersion {
    final ImplementationVersion version
    final String sha

    SnapshotVersion(ImplementationVersion version, String sha) {
        this.version = version
        this.sha = sha
    }

    @Override
    String toString() {
        return "${version} (sha=${sha})"
    }
}
