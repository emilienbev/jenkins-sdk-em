package com.couchbase.tools.versions

import com.couchbase.versions.CppVersions
import com.couchbase.versions.DotNetVersions
import com.couchbase.versions.GoVersions
import com.couchbase.versions.JVMVersions
import com.couchbase.versions.NodeVersions
import com.couchbase.versions.PythonVersions
import com.couchbase.versions.RubyVersions
import com.couchbase.versions.RustVersions

class ListSnapshotVersions {
    static void main(String[] args) {
        def results = new LinkedHashMap<String, String>()

        // These work, not wasting Github hits
        results.put("Java", compute { JVMVersions.getLatestSnapshotBuild("java-client").toString() })
        results.put("Kotlin", compute { JVMVersions.getLatestSnapshotBuild("kotlin-client").toString() })
        results.put("Scala", compute { JVMVersions.getLatestSnapshotBuild("scala-client_2.12").toString() })
        results.put("Go", compute { GoVersions.getLatestGoModEntry() ?: "Unavailable" })

        results.put(".NET", compute { DotNetVersions.getLatestSnapshot().version.toString() })
        results.put("Python", compute { PythonVersions.getLatestSnapshot().version.toString() })
        results.put("Node", compute { NodeVersions.getLatestSnapshot().version.toString() })
        results.put("Ruby", compute { RubyVersions.getLatestSnapshot().version.toString() })

        // These still involve expensive calls to getSnapshotAttributes.  If you see GitHub 403s, comment these out then retry after an hour:
         results.put("C++", compute { CppVersions.getLatestSnapshot().version.toString() })
         results.put("Rust", compute { RustVersions.getLatestSnapshot().version.toString() })

        results.each { sdk, version ->
            println "${sdk}: ${version}"
        }
    }

    private static String compute(Closure<String> supplier) {
        return safeString { silenceIo { supplier.call() } }
    }

    private static String safeString(Closure<String> supplier) {
        try {
            return supplier.call()
        } catch (Throwable err) {
            return "Error: ${err.message}"
        }
    }

    // Hide the spam from failing to parse some versions
    private static <T> T silenceIo(Closure<T> supplier) {
        PrintStream originalOut = System.out
        PrintStream originalErr = System.err
        def outBuffer = new ByteArrayOutputStream()
        def errBuffer = new ByteArrayOutputStream()
        try {
            System.setOut(new PrintStream(outBuffer))
            System.setErr(new PrintStream(errBuffer))
            return supplier.call()
        } finally {
            System.setOut(originalOut)
            System.setErr(originalErr)
        }
    }
}
