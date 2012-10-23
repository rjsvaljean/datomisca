import org.specs2.mutable._

import org.junit.runner.RunWith
import org.specs2.runner.JUnitRunner

import datomic.Entity
import datomic.Connection
import datomic.Database
import datomic.Peer
import datomic.Util

import scala.collection.JavaConverters._
import scala.collection.JavaConversions._

import java.io.Reader
import java.io.FileReader

import scala.concurrent._
import scala.concurrent.util._
import java.util.concurrent.TimeUnit._

import reactivedatomic._
import scala.concurrent.ExecutionContext.Implicits.global

@RunWith(classOf[JUnitRunner])
class DatomicCompilerSpec extends Specification {
  "Datomic" should {
    "query simple" in {
      import Datomic._
      import DatomicData._

      implicit val uri = "datomic:mem://datomicqueryspec"
      DatomicBootstrap(uri)

      val q = DatomicCompiler.query[Args0, Args2]("""
        [ :find ?e ?n 
          :where  [ ?e :person/name ?n ] 
                  [ ?e :person/character :person.character/violent ]
        ]
      """)

      q(Args0()).map{ l => l.collect {
        case Args2(e: DLong, n: DString) => 
          val entity = database.entity(e)
          println("Q2 entity: "+ e + " name:"+n+ " - e:" + entity.get(":person/character"))
        case x => println(x)
      }}.recover{ case e => println("Exception: %s".format(e.getMessage)) }


      /*val query2 = DatomicCompiler.query[Args2, Args3]("""
        [ :find ?e ?name ?age
          :in $ ?age
          :where  [ ?e :person/name ?name ] 
                  [ ?e :person/age ?age ]
                  [ ?e :person/character :person.character/violent ]
        ]
      """)

      val qf = query2.prepare.execute(database, DInt(45)).map( _.collect {
        case (e: DLong, n: DString, a: DInt) => 
          val entity = database.entity(e)
          println("Q2 entity: "+ e + " name:"+n+ " - e:" + entity.get(":person/character"))
        case x => println(x)
      }).recover{ case e => println(e.getMessage) }

      //)

      DatomicCompiler.query[Args2, Args3]("""
        [ :find ?e ?name ?age
          :in $ ?age
          :where  [ ?e :person/name ?name ] 
                  [ ?e :person/age ?a ]
                  [ (< ?a ?age) ]
        ]
      """).prepare.execute(database, DLong(30)).map( _.map {
        case (entity: DLong, name: DString, age: DLong) => 
          println(s"""Q3 entity: $entity - name: $name - age: $age""")
          name must beEqualTo(DString("toto"))
        case x => println("Not Expected: "+x)
      }).recover{ case e => println(e.getMessage) }
*/
      success
    }
  }
}