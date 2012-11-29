import org.specs2.mutable._

import org.junit.runner.RunWith
import org.specs2.runner.JUnitRunner
import org.specs2.specification.{Step, Fragments}

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
import scala.concurrent.{Future, Promise}
import scala.concurrent.duration.Duration
import scala.concurrent.duration._
import scala.util.{Try, Success, Failure}
import java.util.concurrent.TimeUnit._

import reactivedatomic._

import Datomic._
import DatomicData._
import EntityImplicits._

class DatomicTxSpec extends Specification {
  sequential

  import scala.concurrent.ExecutionContext.Implicits.global

  val uri = "datomic:mem://datomictxspec"
  case class Person(name: String, age: Int)
  case class Dog(name: String, age: Int)
  case class PersonFriend(name: String, age: Int)
  case class PersonDog(name: String, age: Int, dog: Ref[Dog])
  case class PersonDogOpt(name: String, age: Int, dog: Option[Ref[Dog]])
  case class PersonDogList(name: String, age: Int, dogs: Set[Ref[Dog]])

  case class PersonLike(name: String, age: Int, like: Option[String] = None)
  case class PersonLikes(name: String, age: Int, likes: Set[String] = Set())

  object PersonSchema {
    val name = Attribute( KW(":person/name"), SchemaType.string, Cardinality.one).withDoc("Person's name")
    val age = Attribute( KW(":person/age"), SchemaType.long, Cardinality.one).withDoc("Person's age")
    val friend = Attribute( KW(":person/friend"), SchemaType.ref, Cardinality.one).withDoc("Person's friend")
    val dog = Attribute( KW(":person/dog"), SchemaType.ref, Cardinality.one).withDoc("Person's dog")
    val dogs = Attribute( KW(":person/dogs"), SchemaType.ref, Cardinality.many).withDoc("Person's dogs")
    val like = Attribute( KW(":person/like"), SchemaType.string, Cardinality.one).withDoc("Person's like")
    val likes = Attribute( KW(":person/likes"), SchemaType.string, Cardinality.many).withDoc("Person's likes")

    val schema = Seq(name, age, friend, dog, dogs, like, likes)
  }

  object DogSchema {
    val name = Attribute( KW(":dog/name"), SchemaType.string, Cardinality.one).withDoc("Dog's name")
    val age = Attribute( KW(":dog/age"), SchemaType.long, Cardinality.one).withDoc("Dog's age")

    val schema = Seq(name, age)
  }

  val person = new Namespace("person") {
    val character = Namespace("person.character")
  }

  val dog = Namespace("dog")      

  def startDB = {
    println("Creating DB with uri %s: %s".format(uri, createDatabase(uri)))

    implicit val conn = Datomic.connect(uri)  
    
    Await.result(
      transact(PersonSchema.schema ++ DogSchema.schema),
      Duration("2 seconds")
    )
  } 

  def stopDB = {
    deleteDatabase(uri)
    println("Deleted DB")
  }

  override def map(fs: => Fragments) = Step(startDB) ^ fs ^ Step(stopDB)

  "Datomic Entity Mappings" should {
    "1 - map simple entity" in {
      implicit val conn = Datomic.connect(uri)  

      implicit val personReader = (
        PersonSchema.name.read[String] and 
        PersonSchema.age.read[Int]
      )(Person)

      val idToto = DId(Partition.USER)

      val fut = transact(
        addEntity(idToto)(
          person / "name" -> "toto",
          person / "age" -> 30
        )
      ).flatMap{ tx => 
        println("Provisioned data... TX:%s".format(tx))

        println("Resolved Id for toto: temp(%s) real(%s)".format(idToto.toNative, tx.resolve(idToto)))

        tx.resolve(idToto).map { totoId => 
          transact(
            addEntity( DId(Partition.USER) )(
              person / "name" -> "tutu",
              person / "age" -> 54,
              person / "friend" -> totoId
            ),
            addEntity( DId(Partition.USER) )(
              person / "name" -> "tata",
              person / "age" -> 23,
              person / "friend" -> totoId
            )
          ).map{ tx => 
            println("Provisioned more data... TX:%s".format(tx))

            query[Args0, Args1]("""
              [ :find ?e 
                :where [ ?e :person/friend ?f ]
                       [ ?f :person/name "toto" ]
              ]              
            """).all().execute().map{ 
              case List() => failure("no result")
              case l => Utils.sequence(
                  l.map{
                  case e: DLong =>
                    fromEntity[Person](database.entity(e)).map{
                      case p @ Person(name, age) => 
                        println(s"Found person with name $name and age $age")
                        p
                    }
                  case _ => failure("error")
                  }
                ).map( l => l must beEqualTo(List(Person("tutu", 54), Person("tata", 23))) ).get 
            }.get
          }
        }.getOrElse(failure("toto Id not found"))
      }.recover{
        case e => failure(e.getMessage)
      }

      Await.result(
        fut,
        Duration("2 seconds")
      )

    }

    "2 - resolve id of an inserted entity" in {
      implicit val conn = Datomic.connect(uri)  

      implicit val personReader = (
        PersonSchema.name.read[String] and 
        PersonSchema.age.read[Int]
      )(Person)

      val idToto = DId(Partition.USER)
      val idTutu = DId(Partition.USER)
      val idTata = DId(Partition.USER)

      val toto = addEntity(idToto)(
        person / "name" -> "toto",
        person / "age" -> 30
      )

      val fut = transact(
        toto
      ).flatMap{ tx => 
        println("2 Provisioned data... TX:%s".format(tx))

        println("2 Resolved Id for toto: temp(%s) real(%s)".format(idToto.toNative, tx.resolve(idToto)))
        tx.resolve(toto).map{ totoId => 
          transact(
            addEntity(idTutu)(
              person / "name" -> "tutu",
              person / "age" -> 54,
              person / "friend" -> totoId
            ),
            addEntity(idTata)(
              person / "name" -> "tata",
              person / "age" -> 23,
              person / "friend" -> totoId
            )
          ).map{ tx => 
            println("2 Provisioned more data... TX:%s".format(tx))

            query[Args0, Args1]("""
              [ :find ?e 
                :where [ ?e :person/friend ?f ]
                       [ ?f :person/name "toto" ]
              ]
            """).all().execute().map{ 
              case List() => failure("no result")
              case l => l.map{
                  case e: DLong =>
                    fromEntity(database.entity(e)).map {
                      case Person(name, age) => println(s"2 Found person with name $name and age $age")
                    }
                  case _ => failure("error")
                }
                success
            }.get
          }
        }.getOrElse(failure("toto Id not found"))
      }.recover{
        case e => failure(e.getMessage)
      }

      Await.result(
        fut,
        Duration("2 seconds")
      )

    }

    "3 - convert a simple case class to AddEntity" in {
      implicit val conn = Datomic.connect(uri)  

      implicit val personDogWriter = (
        PersonSchema.name.write[String] and 
        PersonSchema.age.write[Int]
      )(unlift(Person.unapply))

      val toto = Person("toto", 30)
      val totoId = DId(Partition.USER)

      val totoAddEntity = addEntity(totoId)(
        person / "name" -> "toto",
        person / "age" -> 30
      )

      toAddEntity(totoId)(toto) must beEqualTo(totoAddEntity)
    }

    "4 - manage case class writing with references" in {
      implicit val conn = Datomic.connect(uri)  

      implicit val dogReader = (
        DogSchema.name.read[String] and 
        DogSchema.age.read[Int]
      )(Dog)

      implicit val dogWriter = (
        DogSchema.name.write[String] and 
        DogSchema.age.write[Int]
      )(unlift(Dog.unapply))

      implicit val personDogReader = (
        PersonSchema.name.read[String] and 
        PersonSchema.age.read[Int] and
        PersonSchema.dog.read[Ref[Dog]]
      )(PersonDog)

      implicit val personDogWriter = (
        PersonSchema.name.write[String] and 
        PersonSchema.age.write[Int] and
        PersonSchema.dog.write[Ref[Dog]]
      )(unlift(PersonDog.unapply))

      val medor = Dog("medor", 5)
      val medorId = DId(Partition.USER)
      val toto = PersonDog("toto", 30, Ref(medorId)(medor))
      val totoId = DId(Partition.USER)

      val totoAddEntity = addEntity(totoId)(
        person / "name" -> "toto",
        person / "age" -> 30,
        person / "dog" -> medorId
      )

      toAddEntity(totoId)(toto).toString must beEqualTo(totoAddEntity.toString)

      val fut = transact(
        toAddEntity(totoId)(toto),
        toAddEntity(medorId)(medor)
      ).map{ tx =>
        println("2 Provisioned more data... TX:%s".format(tx))
        
        tx.resolve(medorId, totoId) match {
          case (Some(medorId), Some(totoId)) => 
            println(s"4 totoId:$totoId medorId:$medorId")
            fromEntity[PersonDog](database.entity(totoId)).map {
              case PersonDog(name, age, dog) => println(s"Found Toto $name $age $dog")
            }.get
          case _ => failure("unable to resolve ids")
        }
      }      

      Await.result(
        fut,
        Duration("2 seconds")
      )
    }


    "5 - manage case class writing with optional field" in {
      implicit val conn = Datomic.connect(uri)  

      implicit val personLikeReader = (
        PersonSchema.name.read[String] and 
        PersonSchema.age.read[Int] and
        PersonSchema.like.readOpt[String]
      )(PersonLike)

      implicit val personLikeWriter = (
        PersonSchema.name.write[String] and 
        PersonSchema.age.write[Int] and
        PersonSchema.like.writeOpt[String]
      )(unlift(PersonLike.unapply))

      val toto = PersonLike("toto", 30, Some("chocolate"))
      val totoId = DId(Partition.USER)

      val tutu = PersonLike("tutu", 45, None)
      val tutuId = DId(Partition.USER)

      val totoAddEntity = addEntity(totoId)(
        person / "name" -> "toto",
        person / "age" -> 30,
        person / "like" -> "chocolate"
      )

      toAddEntity(totoId)(toto).toString must beEqualTo(totoAddEntity.toString)

      val fut = transact(
        toAddEntity(totoId)(toto),
        toAddEntity(tutuId)(tutu)
      ).map{ tx =>
        println("5 - Provisioned more data... TX:%s".format(tx))
        
        tx.resolve(totoId, tutuId) match {
          case (Some(totoId), Some(tutuId)) => 
            println(s"5 - totoId:$totoId tutuId:$tutuId")
            fromEntity[PersonLike](database.entity(totoId)).map { t => 
              println(s"5 - retrieved toto:$t")
              t.toString must beEqualTo(PersonLike("toto", 30, Some("chocolate")).toString)
            }.get

            fromEntity[PersonLike](database.entity(tutuId)).map { t => 
              println(s"5 - retrieved tutu:$t")
              t must beEqualTo(tutu)
            }.get
          case _ => failure("unable to resolve ids")
        }
      }      

      Await.result(
        fut,
        Duration("2 seconds")
      )
    }


    "6 - manage case class writing with list field" in {
      implicit val conn = Datomic.connect(uri)  

      implicit val personLikesReader = (
        PersonSchema.name.read[String] and 
        PersonSchema.age.read[Int] and
        PersonSchema.likes.read[Set[String]]
      )(PersonLikes)

      implicit val personLikesWriter = (
        PersonSchema.name.write[String] and 
        PersonSchema.age.write[Int] and
        PersonSchema.likes.write[Set[String]]
      )(unlift(PersonLikes.unapply))

      val toto = PersonLikes("toto", 30, Set("chocolate", "vanilla"))
      val totoId = DId(Partition.USER)

      val totoAddEntity = addEntity(totoId)(
        person / "name" -> "toto",
        person / "age" -> 30,
        person / "likes" -> Set("chocolate", "vanilla")
      )

      toAddEntity(totoId)(toto).toString must beEqualTo(totoAddEntity.toString)
      val fut = transact(
        toAddEntity(totoId)(toto)
      ).map{ tx =>
        println("5 - Provisioned more data... TX:%s".format(tx))
        
        tx.resolve(totoId) match {
          case Some(totoId) => 
            println(s"6 - totoId:$totoId")
            fromEntity[PersonLikes](database.entity(totoId)).map { t => 
              println(s"5 - retrieved toto:$t")
              t must beEqualTo(PersonLikes("toto", 30, Set("chocolate", "vanilla")))
            }.get
          case _ => failure("unable to resolve id")
        }
      }      

      Await.result(
        fut,
        Duration("2 seconds")
      )
    }

    "7 - manage case class writing with optional references" in {
      implicit val conn = Datomic.connect(uri)  

      implicit val dogReader = (
        DogSchema.name.read[String] and 
        DogSchema.age.read[Int]
      )(Dog)

      implicit val dogWriter = (
        DogSchema.name.write[String] and 
        DogSchema.age.write[Int]
      )(unlift(Dog.unapply))

      implicit val personDogOptReader = (
        PersonSchema.name.read[String] and 
        PersonSchema.age.read[Int] and
        PersonSchema.dog.readOpt[Ref[Dog]]
      )(PersonDogOpt)

      implicit val personDogOptWriter = (
        PersonSchema.name.write[String] and 
        PersonSchema.age.write[Int] and
        PersonSchema.dog.writeOpt[Ref[Dog]]
      )(unlift(PersonDogOpt.unapply))

      val medor = Dog("medor", 5)
      val medorId = DId(Partition.USER)
      val toto = PersonDogOpt("toto", 30, Some(Ref(medorId)(medor)))
      val totoId = DId(Partition.USER)

      val tutu = PersonDogOpt("tutu", 45, None)
      val tutuId = DId(Partition.USER)

      val totoAddEntity = addEntity(totoId)(
        person / "name" -> "toto",
        person / "age" -> 30,
        person / "dog" -> medorId
      )

      toAddEntity(totoId)(toto).toString must beEqualTo(totoAddEntity.toString)

      val fut = transact(
        toAddEntity(totoId)(toto),
        toAddEntity(medorId)(medor),
        toAddEntity(tutuId)(tutu)
      ).map{ tx =>
        println("7 - Provisioned more data... TX:%s".format(tx))
        
        tx.resolve(medorId, totoId, tutuId) match {
          case (Some(medorId), Some(totoId), Some(tutuId)) => 
            println(s"7 - totoId:$totoId medorId:$medorId")
            fromEntity[PersonDogOpt](database.entity(totoId)).map { t => 
              println(s"7 - retrieved toto:$t")
              t.toString must beEqualTo(PersonDogOpt("toto", 30, Some(Ref(DId(medorId))(medor))).toString)
            }.get

            fromEntity[PersonDogOpt](database.entity(tutuId)).map { t => 
              println(s"7 - retrieved tutu:$t")
              t must beEqualTo(tutu)
            }.get
          case _ => failure("unable to resolve ids")
        }
      }      

      Await.result(
        fut,
        Duration("2 seconds")
      )
    }


    "8 - manage case class writing with list references" in {
      implicit val conn = Datomic.connect(uri)  

      implicit val dogReader = (
        DogSchema.name.read[String] and 
        DogSchema.age.read[Int]
      )(Dog)

      implicit val dogWriter = (
        DogSchema.name.write[String] and 
        DogSchema.age.write[Int]
      )(unlift(Dog.unapply))

      implicit val personDogListReader = (
        PersonSchema.name.read[String] and 
        PersonSchema.age.read[Int] and
        PersonSchema.dogs.read[Set[Ref[Dog]]]
      )(PersonDogList)

      implicit val personDogListWriter = (
        PersonSchema.name.write[String] and 
        PersonSchema.age.write[Int] and
        PersonSchema.dogs.write[Set[Ref[Dog]]]
      )(unlift(PersonDogList.unapply))

      val medor = Dog("medor", 5)
      val medorId = DId(Partition.USER)

      val brutus = Dog("brutus", 3)
      val brutusId = DId(Partition.USER)
      
      val toto = PersonDogList("toto", 30, Set(Ref(medorId)(medor), Ref(brutusId)(brutus)))
      val totoId = DId(Partition.USER)

      val totoAddEntity = addEntity(totoId)(
        person / "name" -> "toto",
        person / "age" -> 30,
        person / "dogs" -> Set(medorId, brutusId)
      )

      toAddEntity(totoId)(toto).toString must beEqualTo(totoAddEntity.toString)
      println("8 - toto:"+toto+" TOTO ENTITY:"+toAddEntity(totoId)(toto))

      val fut = transact(
        toAddEntity(totoId)(toto),
        toAddEntity(medorId)(medor),
        toAddEntity(brutusId)(brutus)
      ).map{ tx =>
        println("8 - Provisioned more data... TX:%s".format(tx))
        
        tx.resolve(medorId, brutusId, totoId) match {
          case (Some(medorId), Some(brutusId), Some(totoId)) => 
            fromEntity[PersonDogList](database.entity(totoId)).map{ t => 
              t must beEqualTo(PersonDogList("toto", 30, Set(Ref(DId(medorId))(medor), Ref(DId(brutusId))(brutus))))
            }.get
          case _ => failure("unable to resolve ids")
        }
      }      

      Await.result(
        fut,
        Duration("2 seconds")
      )
    }
  }

}