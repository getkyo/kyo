package com.example.sql

import kyo.*
import kyo.internal.SqliteConformanceBackend

/** The battery subclassed from outside `package kyo`, the way a backend written elsewhere runs it. A member the battery needs that is not
  * public fails this file's compile. SQLite because it needs no container.
  */
class SqlExternalPackageTest extends SqlConformanceTest(Seq(new SqliteConformanceBackend))
