package com.mesosphere.cosmos

import cats.data.Xor.Right
import com.mesosphere.cosmos.handler._
import com.mesosphere.cosmos.http.MediaTypes
import com.mesosphere.cosmos.model._
import com.netaporter.uri.Uri
import com.twitter.finagle.http._
import com.twitter.io.Buf
import com.twitter.finagle.{Http, Service}
import com.twitter.util._
import io.circe.parse._
import com.mesosphere.cosmos.circe.Decoders._
import com.mesosphere.cosmos.circe.Encoders._
import io.circe.syntax._
import org.scalatest.FreeSpec

final class PackageSearchSpec extends FreeSpec with CosmosSpec {

  import IntegrationHelpers._
  import PackageSearchSpec._

  "The package search endpoint" - {
    "can successfully find packages" in {
      val _ = withTempDirectory { universeDir =>
        val universeCache = Await.result(UniversePackageCache(UniverseUri, universeDir))

        runService(packageCache = universeCache) { apiClient =>
          forAll (PackageSearchTable) { (query, expectedResponse) =>
            apiClient.searchAndAssert(
              query=query,
              status=Status.Ok,
              expectedResponse=SearchResponse(expectedResponse)
            )
          }
        }
      }
    }

    "can successfully find packages by regex match" in {
      val _ = withTempDirectory { universeDir =>
        val universeCache = Await.result(UniversePackageCache(UniverseUri, universeDir))

        runService(packageCache = universeCache) { apiClient =>
          forAll (PackageSearchRegexTable) { (query, expectedResponse) =>
            apiClient.searchAndAssert(
              query=query,
              status=Status.Ok,
              expectedResponse=SearchResponse(expectedResponse)
            )
          }
        }
      }
    }
  }

  private[this] def runService[A](
    dcosClient: Service[Request, Response] = Services.adminRouterClient(adminRouterHost).get,
    packageCache: PackageCache
  )(
    f: SearchTestAssertionDecorator => Unit
  ): Unit = {
    // these two imports provide the implicit DecodeRequest instances needed to instantiate Cosmos
    import io.finch.circe._
    val marathonPackageRunner = new MarathonPackageRunner(adminRouter)
    //TODO: Get rid of this duplication
    val service = new Cosmos(
      packageCache,
      marathonPackageRunner,
      new UninstallHandler(adminRouter, packageCache),
      new PackageInstallHandler(packageCache, marathonPackageRunner),
      new PackageRenderHandler(packageCache),
      new PackageSearchHandler(packageCache),
      new PackageImportHandler,
      new PackageDescribeHandler(packageCache),
      new ListVersionsHandler(packageCache),
      new ListHandler(adminRouter, packageCache)
    ).service
    val server = Http.serve(s":$servicePort", service)
    val client = Http.newService(s"127.0.0.1:$servicePort")

    try {
      f(new SearchTestAssertionDecorator(client))
    } finally {
      Await.all(server.close(), client.close(), service.close())
    }
  }
}

private object PackageSearchSpec extends CosmosSpec {

  private val UniverseUri = Uri.parse("https://github.com/mesosphere/universe/archive/cli-test-4.zip")

  val ArangodbPackageIndex = UniverseIndexEntry(
    name = "arangodb",
    currentVersion = "0.2.1",
    versions = Map("0.2.1" -> "0"),
    description = "A distributed free and open-source database with a flexible data model for documents, graphs, and key-values. " +
                  "Build high performance applications using a convenient SQL-like query language or JavaScript extensions.",
    framework = true,
    tags = List("arangodb", "NoSQL", "database", "framework")
  )

  val CassandraPackageIndex = UniverseIndexEntry(
    name = "cassandra",
    currentVersion = "0.2.0-1",
    versions = Map("0.2.0-1" -> "0"),
    description = "Apache Cassandra running on Apache Mesos",
    framework = true,
    tags = List("data", "database", "nosql")
  )

  val MemsqlPackageIndex = UniverseIndexEntry(
    name = "memsql",
    currentVersion = "0.0.1",
    versions = Map("0.0.1" -> "0"),
    description = "MemSQL running on Apache Mesos. This framework provides the ability to create and manage a set of MemSQL clusters, " +
                  "each running with the MemSQL Ops management tool.",
    framework = true,
    tags = List("mysql", "database", "rdbms")
  )

  private val PackageSearchTable = Table(
    ("query", "response"),
    ("aran", List(ArangodbPackageIndex)),
    ("cass", List(CassandraPackageIndex)),
    ("cassan", List(CassandraPackageIndex)),
    ("databas", List(ArangodbPackageIndex, CassandraPackageIndex))
  )

  private val PackageSearchRegexTable = Table(
    ("query", "response"),
    ("cassan*a", List(CassandraPackageIndex)),
    ("c*a", List(CassandraPackageIndex)),
    ("cass*", List(CassandraPackageIndex)),
    ("data*e", List(ArangodbPackageIndex, CassandraPackageIndex))
  )
}

private final class SearchTestAssertionDecorator(apiClient: Service[Request, Response]) extends CosmosSpec {

  val SearchEndpoint = "v1/package/search"

  private[cosmos] def searchAndAssert(
    query: String,
    status: Status,
    expectedResponse: SearchResponse
  ): Unit = {
    val response = searchRequest(apiClient, SearchRequest(Some(query)))
    assertResult(status)(response.status)
    val Right(actualResponse) = decode[SearchResponse](response.contentString)
    assertResult(expectedResponse)(actualResponse)
  }

  private[this] def searchRequest(
    apiClient: Service[Request, Response],
    searchRequest: SearchRequest
  ): Response = {
    val request = requestBuilder(SearchEndpoint)
      .addHeader("Content-Type", MediaTypes.SearchRequest.show)
      .addHeader("Accept", MediaTypes.SearchResponse.show)
      .buildPost(Buf.Utf8(searchRequest.asJson.noSpaces))
    Await.result(apiClient(request))
  }
}


