/*
 * Copyright 2015 Midokura SARL
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.midonet.cluster.data.storage

import java.util.UUID

import scala.concurrent.duration._
import scala.reflect.classTag

import org.apache.zookeeper.KeeperException.Code
import org.junit.runner.RunWith
import org.scalatest.{BeforeAndAfter, FeatureSpec, GivenWhenThen, Matchers}
import org.scalatest.junit.JUnitRunner

import rx.Observable
import rx.observers.TestObserver
import rx.subjects.PublishSubject

import org.midonet.cluster.data.storage.InMemoryStorage.DefaultOwnerId
import org.midonet.cluster.data.storage.StorageTestClasses.State
import org.midonet.cluster.data.storage.StateStorage._
import org.midonet.cluster.data.storage.KeyType._
import org.midonet.util.reactivex._
import org.midonet.util.reactivex.AwaitableObserver
import ZookeeperStateTableTest.ScoreStateTable

@RunWith(classOf[JUnitRunner])
class InMemoryStateStorageTest extends FeatureSpec with BeforeAndAfter
                               with Matchers with GivenWhenThen {

    private var storage: InMemoryStorage = _
    private var namespaceId: String = _
    private final val timeout = 1 second

    before {
        storage = new InMemoryStorage
        namespaceId = storage.namespace
        storage.registerClass(classOf[State])
        storage.registerKey(classOf[State], "first", SingleFirstWriteWins)
        storage.registerKey(classOf[State], "last", SingleLastWriteWins)
        storage.registerKey(classOf[State], "multi", Multiple)
        storage.registerTable(classOf[Int], classOf[String], "global-table",
                              classOf[ScoreStateTable])

        storage.build()
    }

    feature("Test non-existing key") {
        scenario("Add for non-existing key") {
            Given("Any object identifier and non-existing key")
            val id = UUID.randomUUID()
            Then("Adding a value the method should throw an exception")
            intercept[IllegalArgumentException] {
                storage.addValue(classOf[State], id, "absent", "nonce")
            }
        }

        scenario("Get for non-existing key") {
            Given("Any object identifier and non-existing key")
            val id = UUID.randomUUID()
            Then("Reading the key the method should throw an exception")
            intercept[IllegalArgumentException] {
                storage.getKey(classOf[State], id, "absent")
            }
        }

        scenario("Remove for non-existing key") {
            Given("Any object identifier and non-existing key")
            val id = UUID.randomUUID()
            Then("Removing a value the method should throw an exception")
            intercept[IllegalArgumentException] {
                storage.removeValue(classOf[State], id, "absent", null)
            }
        }
    }

    def testAddNonExistingObject(key: String): Unit = {
        Given("A non-existing object identifier")
        val id = UUID.randomUUID
        Then("Adding a value, the future should fail")
        val e = intercept[UnmodifiableStateException] {
            storage.addValue(classOf[State], id, key, "nonce").await(timeout)
        }

        e.clazz shouldBe classOf[State].getSimpleName
        e.id shouldBe id.toString
        e.key shouldBe key
        e.value shouldBe "nonce"
        e.result shouldBe Code.NONODE.intValue()
    }

    def testGetSingleValueNonExistingObject(key: String): Unit = {
        Given("A non-existing object identifier")
        val id = UUID.randomUUID()
        Then("Reading the key, the future should return None")
        storage.getKey(classOf[State], id, key)
            .await(timeout) shouldBe SingleValueKey(key, None, NoOwnerId)
    }

    def testGetMultiValueNonExistingObject(key: String): Unit = {
        Given("A non-existing object identifier")
        val id = UUID.randomUUID()
        Then("Reading the key, the future should return an empty Set")
        storage.getKey(classOf[State], id, key)
            .await(timeout) shouldBe MultiValueKey(key, Set())
    }

    def testRemoveNonExistingObject(key: String): Unit = {
        Given("A non-existing object identifier")
        val id = UUID.randomUUID()
        Then("Removing a value, the future should return None")
        storage.removeValue(classOf[State], id, key, null)
            .await(timeout) shouldBe StateResult(NoOwnerId)
    }

    def testAddGetRemoveSingleValue(key: String): Unit = {
        Given("An object in storage")
        val obj = new State
        storage.create(obj)

        Then("Adding a value should return the current session")
        storage.addValue(classOf[State], obj.id, key, "1")
            .await(timeout) shouldBe StateResult(DefaultOwnerId)
        And("Reading the key should return the added value")
        storage.getKey(classOf[State], obj.id, key)
            .await(timeout) shouldBe SingleValueKey(key, Some("1"),
                                                    DefaultOwnerId)
        And("Removing the value should return the current session")
        storage.removeValue(classOf[State], obj.id, key, null)
            .await(timeout) shouldBe StateResult(DefaultOwnerId)
        And("Reading the key again should return no value")
        storage.getKey(classOf[State], obj.id, key)
            .await(timeout) shouldBe SingleValueKey(key, None, NoOwnerId)
    }

    def testAddSingleSameClient(key: String): Unit = {
        Given("An object in storage")
        val obj = new State
        storage.create(obj)

        Then("Adding a value should return the current session")
        storage.addValue(classOf[State], obj.id, key, "1")
            .await(timeout) shouldBe StateResult(DefaultOwnerId)
        And("Adding a second value should return the current session")
        storage.addValue(classOf[State], obj.id, key, "2")
            .await(timeout) shouldBe StateResult(DefaultOwnerId)
        And("Reading the key should return the second value")
        storage.getKey(classOf[State], obj.id, key)
            .await(timeout) shouldBe SingleValueKey(key, Some("2"),
                                                    DefaultOwnerId)
    }

    feature("Test single value with first write wins") {
        scenario("Add for non-existing object") {
            testAddNonExistingObject("first")
        }

        scenario("Get for non-existing object") {
            testGetSingleValueNonExistingObject("first")
        }

        scenario("Remove for non-existing object") {
            testRemoveNonExistingObject("first")
        }

        scenario("Add, get, remove value for object") {
            testAddGetRemoveSingleValue("first")
        }

        scenario("Add another value for the same client") {
            testAddSingleSameClient("first")
        }
    }

    feature("Test single value with last write wins") {
        scenario("Add for non-existing object") {
            testAddNonExistingObject("last")
        }

        scenario("Get for non-existing object") {
            testGetSingleValueNonExistingObject("last")
        }

        scenario("Remove for non-existing object") {
            testRemoveNonExistingObject("last")
        }

        scenario("Add, get, remove value for object") {
            testAddGetRemoveSingleValue("last")
        }

        scenario("Add another value for the same client") {
            testAddSingleSameClient("last")
        }
    }

    feature("Test multiple value") {
        scenario("Add for non-existing object") {
            testAddNonExistingObject("multi")
        }

        scenario("Get for non-existing object") {
            testGetMultiValueNonExistingObject("multi")
        }

        scenario("Remove for non-existing object") {
            testRemoveNonExistingObject("multi")
        }

        scenario("Add, get, remove value for object by single client") {
            Given("An object in storage")
            val obj = new State
            storage.create(obj)

            Then("Adding a value should return the current session")
            storage.addValue(classOf[State], obj.id, "multi", "1")
                .await(timeout) shouldBe StateResult(DefaultOwnerId)
            And("Reading the key should return the added value")
            storage.getKey(classOf[State], obj.id, "multi")
                .await(timeout) shouldBe MultiValueKey("multi", Set("1"))

            When("Adding a value should return the current session")
            storage.addValue(classOf[State], obj.id, "multi", "2")
                .await(timeout) shouldBe StateResult(DefaultOwnerId)
            And("Reading the key should return all added value")
            storage.getKey(classOf[State], obj.id, "multi")
                .await(timeout) shouldBe MultiValueKey("multi", Set("1", "2"))

            When("Removing a value should return the current session")
            storage.removeValue(classOf[State], obj.id, "multi", "1")
                .await(timeout) shouldBe StateResult(DefaultOwnerId)
            And("Reading the key again should return the other value")
            storage.getKey(classOf[State], obj.id, "multi")
                .await(timeout) shouldBe MultiValueKey("multi", Set("2"))

            When("Removing the other value should return the current session")
            storage.removeValue(classOf[State], obj.id, "multi", "2")
                .await(timeout) shouldBe StateResult(DefaultOwnerId)
            And("Reading the key again should return no value")
            storage.getKey(classOf[State], obj.id, "multi")
                .await(timeout) shouldBe MultiValueKey("multi", Set())
        }
    }

    feature("Test observables for single value") {
        scenario("Object does not exist") {
            Given("A random object identifier")
            val id = UUID.randomUUID()

            When("An observer subscribes to the key")
            val obs = new TestObserver[StateKey] with AwaitableObserver[StateKey]
            storage.keyObservable(classOf[State], id, "first")
                .subscribe(obs)

            Then("The observer should complete")
            obs.awaitCompletion(timeout)
            obs.getOnNextEvents shouldBe empty
        }

        scenario("Value does not exist") {
            Given("An object in storage")
            val obj = new State
            storage.create(obj)

            When("An observer subscribes to the key")
            val obs = new TestObserver[StateKey] with AwaitableObserver[StateKey]
            storage.keyObservable(classOf[State], obj.id, "first")
                .subscribe(obs)

            Then("The observer receives no value")
            obs.awaitOnNext(1, timeout) shouldBe true
            obs.getOnNextEvents.get(0) shouldBe SingleValueKey(
                "first", None, NoOwnerId)
        }

        scenario("Value exists in storage") {
            Given("An object in storage")
            val obj = new State
            storage.create(obj)

            And("A key value")
            storage.addValue(classOf[State], obj.id, "first", "1")
                .await(timeout)

            When("An observer subscribed to the key")
            val obs = new TestObserver[StateKey] with AwaitableObserver[StateKey]
            storage.keyObservable(classOf[State], obj.id, "first")
                .subscribe(obs)

            Then("The observer receives the value")
            obs.awaitOnNext(1, timeout) shouldBe true
            obs.getOnNextEvents.get(0) shouldBe SingleValueKey(
                "first", Some("1"), DefaultOwnerId)
        }

        scenario("Value is created") {
            Given("An object in storage")
            val obj = new State
            storage.create(obj)

            When("An observer subscribed to the key")
            val obs = new TestObserver[StateKey] with AwaitableObserver[StateKey]
            storage.keyObservable(classOf[State], obj.id, "first")
                .subscribe(obs)

            Then("The observer receives no value")
            obs.awaitOnNext(1, timeout) shouldBe true
            obs.getOnNextEvents.get(0) shouldBe SingleValueKey(
                "first", None, NoOwnerId)

            When("Adding a key value")
            storage.addValue(classOf[State], obj.id, "first", "1")
                .await(timeout)

            Then("The observer receives the just added value")
            obs.awaitOnNext(2, timeout) shouldBe true
            obs.getOnNextEvents.get(1) shouldBe SingleValueKey(
                "first", Some("1"), DefaultOwnerId)
        }

        scenario("Value is deleted and recreated") {
            Given("An object in storage")
            val obj = new State
            storage.create(obj)

            And("A key value")
            storage.addValue(classOf[State], obj.id, "first", "1")
                .await(timeout)

            When("An observer subscribed to the key")
            val obs = new TestObserver[StateKey] with AwaitableObserver[StateKey]
            storage.keyObservable(classOf[State], obj.id, "first")
                .subscribe(obs)

            And("The value is deleted")
            storage.removeValue(classOf[State], obj.id, "first", null)
                .await(timeout)

            And("A new value is added")
            storage.addValue(classOf[State], obj.id, "first", "2")
                .await(timeout)

            Then("The observer receives all updates")
            obs.awaitOnNext(3, timeout) shouldBe true
            obs.getOnNextEvents.get(0) shouldBe SingleValueKey(
                "first", Some("1"), DefaultOwnerId)
            obs.getOnNextEvents.get(1) shouldBe SingleValueKey(
                "first", None, NoOwnerId)
            obs.getOnNextEvents.get(2) shouldBe SingleValueKey(
                "first", Some("2"), DefaultOwnerId)
        }

        scenario("Observable completes on object deletion") {
            Given("An object in storage")
            val obj = new State
            storage.create(obj)

            And("A key value")
            storage.addValue(classOf[State], obj.id, "first", "1")
                .await(timeout)

            And("An observer subscribed to the key")
            val obs = new TestObserver[StateKey] with AwaitableObserver[StateKey]
            storage.keyObservable(classOf[State], obj.id, "first")
                .subscribe(obs)
            obs.awaitOnNext(1, timeout) shouldBe true

            When("The object is deleted")
            storage.delete(classOf[State], obj.id)

            Then("The observable should complete")
            obs.awaitCompletion(timeout)
        }

        scenario("Observable notifies identical values") {
            Given("An object in storage")
            val obj = new State
            storage.create(obj)

            And("A key value")
            storage.addValue(classOf[State], obj.id, "first", "1")
                .await(timeout)

            When("An observer subscribed to the key")
            val obs = new TestObserver[StateKey] with AwaitableObserver[StateKey]
            storage.keyObservable(classOf[State], obj.id, "first")
                .subscribe(obs)

            And("The same value is added two more times")
            storage.addValue(classOf[State], obj.id, "first", "1")
                .await(timeout)
            storage.addValue(classOf[State], obj.id, "first", "1")
                .await(timeout)

            And("Removing the value")
            storage.removeValue(classOf[State], obj.id, "first", null)
                .await(timeout)

            Then("The observer receives all updates")
            obs.awaitOnNext(4, timeout) shouldBe true
            obs.getOnNextEvents.get(0) shouldBe SingleValueKey(
                "first", Some("1"), DefaultOwnerId)
            obs.getOnNextEvents.get(1) shouldBe SingleValueKey(
                "first", Some("1"), DefaultOwnerId)
            obs.getOnNextEvents.get(2) shouldBe SingleValueKey(
                "first", Some("1"), DefaultOwnerId)
            obs.getOnNextEvents.get(3) shouldBe SingleValueKey(
                "first", None, NoOwnerId)
        }
    }

    feature("Test observable for multi value") {
        scenario("Object does not exist") {
            Given("A random object identifier")
            val id = UUID.randomUUID()

            When("An observer subscribes to the key")
            val obs = new TestObserver[StateKey] with AwaitableObserver[StateKey]
            storage.keyObservable(classOf[State], id, "multi")
                .subscribe(obs)

            Then("The observer should complete")
            obs.awaitCompletion(timeout)
            obs.getOnNextEvents shouldBe empty
        }

        scenario("Value does not exist") {
            Given("An object in storage")
            val obj = new State
            storage.create(obj)

            When("An observer subscribes to the key")
            val obs = new TestObserver[StateKey] with AwaitableObserver[StateKey]
            storage.keyObservable(classOf[State], obj.id, "multi")
                .subscribe(obs)

            Then("The observer receives no value")
            obs.awaitOnNext(1, timeout) shouldBe true
            obs.getOnNextEvents.get(0) shouldBe MultiValueKey("multi", Set())
        }

        scenario("Values exist in storage") {
            Given("An object in storage")
            val obj = new State
            storage.create(obj)

            And("A key value")
            storage.addValue(classOf[State], obj.id, "multi", "1")
                .await(timeout)
            storage.addValue(classOf[State], obj.id, "multi", "2")
                .await(timeout)
            storage.addValue(classOf[State], obj.id, "multi", "3")
                .await(timeout)

            When("An observer subscribed to the key")
            val obs = new TestObserver[StateKey] with AwaitableObserver[StateKey]
            storage.keyObservable(classOf[State], obj.id, "multi")
                .subscribe(obs)

            Then("The observer receives the value")
            obs.awaitOnNext(1, timeout) shouldBe true
            obs.getOnNextEvents.get(0) shouldBe MultiValueKey(
                "multi", Set("1", "2", "3"))
        }

        scenario("Values are added and deleted") {
            Given("An object in storage")
            val obj = new State
            storage.create(obj)

            And("A key value")
            storage.addValue(classOf[State], obj.id, "multi", "1")
                .await(timeout)

            When("An observer subscribed to the key")
            val obs = new TestObserver[StateKey] with AwaitableObserver[StateKey]
            storage.keyObservable(classOf[State], obj.id, "multi")
                .subscribe(obs)

            And("Another value is added")
            storage.addValue(classOf[State], obj.id, "multi", "2")
                .await(timeout)

            And("The first value is deleted")
            storage.removeValue(classOf[State], obj.id, "multi", "1")
                .await(timeout)

            And("The second value is deleted")
            storage.removeValue(classOf[State], obj.id, "multi", "2")
                .await(timeout)

            And("Another value is added")
            storage.addValue(classOf[State], obj.id, "multi", "3")
                .await(timeout)


            Then("The observer receives all updates")
            obs.awaitOnNext(5, timeout) shouldBe true
            obs.getOnNextEvents.get(0) shouldBe MultiValueKey(
                "multi", Set("1"))
            obs.getOnNextEvents.get(1) shouldBe MultiValueKey(
                "multi", Set("1", "2"))
            obs.getOnNextEvents.get(2) shouldBe MultiValueKey(
                "multi", Set("2"))
            obs.getOnNextEvents.get(3) shouldBe MultiValueKey(
                "multi", Set())
            obs.getOnNextEvents.get(4) shouldBe MultiValueKey(
                "multi", Set("3"))
        }

        scenario("Observable completes on object deletion") {
            Given("An object in storage")
            val obj = new State
            storage.create(obj)

            And("A key value")
            storage.addValue(classOf[State], obj.id, "multi", "1")
                .await(timeout)

            And("An observer subscribed to the key")
            val obs = new TestObserver[StateKey] with AwaitableObserver[StateKey]
            storage.keyObservable(classOf[State], obj.id, "multi")
                .subscribe(obs)
            obs.awaitOnNext(1, timeout) shouldBe true

            When("The object is deleted")
            storage.delete(classOf[State], obj.id)

            Then("The observable should complete")
            obs.awaitCompletion(timeout)
        }


        scenario("Observable does not notify identical values") {
            Given("An object in storage")
            val obj = new State
            storage.create(obj)

            And("A key value")
            storage.addValue(classOf[State], obj.id, "multi", "1")
                .await(timeout)

            When("An observer subscribed to the key")
            val obs = new TestObserver[StateKey] with AwaitableObserver[StateKey]
            storage.keyObservable(classOf[State], obj.id, "multi")
                .subscribe(obs)

            And("The same value is added two more times")
            storage.addValue(classOf[State], obj.id, "multi", "1")
                .await(timeout)
            storage.addValue(classOf[State], obj.id, "multi", "1")
                .await(timeout)

            And("Removing the value")
            storage.removeValue(classOf[State], obj.id, "multi", "1")
                .await(timeout)

            Then("The observer receives just one update per value")
            obs.awaitOnNext(2, timeout) shouldBe true
            obs.getOnNextEvents.get(0) shouldBe MultiValueKey(
                "multi", Set("1"))
            obs.getOnNextEvents.get(1) shouldBe MultiValueKey(
                "multi", Set())
        }
    }

    def testMultiNamespaceAddReadDeleteSingle(key: String): Unit = {
        Given("Two storage clients")
        val namespaceId2 = UUID.randomUUID().toString

        And("An object in storage")
        val obj = new State
        storage.create(obj)

        When("The first namespace adds a value")
        storage.addValue(classOf[State], obj.id, key, "1")
            .await(timeout) shouldBe StateResult(DefaultOwnerId)

        Then("The value is present for the first namespace")
        storage.getKey(classOf[State], obj.id, key)
            .await(timeout) shouldBe SingleValueKey(key, Some("1"), DefaultOwnerId)
        storage.getKey(namespaceId, classOf[State], obj.id, key)
            .await(timeout) shouldBe SingleValueKey(key, Some("1"), DefaultOwnerId)

        And("The value is not present for the second namespace")
        storage.getKey(namespaceId2, classOf[State], obj.id, key)
            .await(timeout) shouldBe SingleValueKey(key, None, NoOwnerId)

        When("The second namespace adds a value")
        storage.addValueAs(namespaceId2, classOf[State], obj.id, key, "2")
            .await(timeout) shouldBe StateResult(DefaultOwnerId)

        Then("The key is unmodified for the first namespace")
        storage.getKey(classOf[State], obj.id, key)
            .await(timeout) shouldBe SingleValueKey(key, Some("1"), DefaultOwnerId)
        storage.getKey(namespaceId, classOf[State], obj.id, key)
            .await(timeout) shouldBe SingleValueKey(key, Some("1"), DefaultOwnerId)

        And("The value is present for the second namespace")
        storage.getKey(namespaceId2, classOf[State], obj.id, key)
            .await(timeout) shouldBe SingleValueKey(key, Some("2"), DefaultOwnerId)

        When("The first namespace removes the value")
        storage.removeValue(classOf[State], obj.id, key, null)
            .await(timeout) shouldBe StateResult(DefaultOwnerId)

        Then("The value is not present for the first namespace")
        storage.getKey(classOf[State], obj.id, key)
            .await(timeout) shouldBe SingleValueKey(key, None, NoOwnerId)
        storage.getKey(namespaceId, classOf[State], obj.id, key)
            .await(timeout) shouldBe SingleValueKey(key, None, NoOwnerId)

        And("The value is unmodified for the second namespace")
        storage.getKey(namespaceId2, classOf[State], obj.id, key)
            .await(timeout) shouldBe SingleValueKey(key, Some("2"), DefaultOwnerId)

        When("The second namespace removes the value")
        storage.removeValueAs(namespaceId2, classOf[State], obj.id, key, null)
            .await(timeout) shouldBe StateResult(DefaultOwnerId)

        Then("The value is removed for the second namespace")
        storage.getKey(namespaceId2, classOf[State], obj.id, key)
            .await(timeout) shouldBe SingleValueKey(key, None, NoOwnerId)
    }

    feature("Test state storage for multiple namespaces") {
        scenario("Get single value key returns empty for null namespace") {
            Given("An object in storage")
            val obj = new State
            storage.create(obj)

            Then("Requesting the key for a null namespace returns none")
            storage.getKey(null, classOf[State], obj.id, "first")
                .await(timeout) shouldBe SingleValueKey("first", None, NoOwnerId)
        }

        scenario("Get multi value key returns empty for null namespace") {
            Given("An object in storage")
            val obj = new State
            storage.create(obj)

            Then("Requesting the key for a null namespace return an empty set")
            storage.getKey(null, classOf[State], obj.id, "multi")
                .await(timeout) shouldBe MultiValueKey("multi", Set())
        }

        scenario("Single value key observable emits empty for null namespace") {
            Given("An object in storage")
            val obj = new State
            storage.create(obj)

            And("A null namespace")
            val namespace: String = null

            When("An observer from the first namespace subscribes")
            val obs = new TestObserver[StateKey] with AwaitableObserver[StateKey]
            storage.keyObservable(namespace, classOf[State], obj.id, "first")
                .subscribe(obs)

            Then("The observer receives an empty state and then completes")
            obs.awaitOnNext(1, timeout)
            obs.getOnNextEvents.get(0) shouldBe SingleValueKey(
                "first", None, NoOwnerId)
            obs.awaitCompletion(timeout)
            obs.getOnCompletedEvents should not be empty
        }

        scenario("Multi value key observable emits empty for null namespace") {
            Given("An object in storage")
            val obj = new State
            storage.create(obj)

            And("A null namespace")
            val namespace: String = null

            When("An observer from the first namespace subscribes")
            val obs = new TestObserver[StateKey] with AwaitableObserver[StateKey]
            storage.keyObservable(namespace, classOf[State], obj.id, "multi")
                .subscribe(obs)

            Then("The observer receives an empty state and then completes")
            obs.awaitOnNext(1, timeout)
            obs.getOnNextEvents.get(0) shouldBe MultiValueKey("multi", Set())
            obs.awaitCompletion(timeout)
            obs.getOnCompletedEvents should not be empty
        }

        scenario("Single value key namespaces observable emits empty for null namespace") {
            Given("An object in storage")
            val obj = new State
            storage.create(obj)

            And("A null namespaces observable")
            val namespaces = Observable.just[String](null)

            When("An observer from the first namespace subscribes")
            val obs = new TestObserver[StateKey] with AwaitableObserver[StateKey]
            storage.keyObservable(namespaces, classOf[State], obj.id, "first")
                .subscribe(obs)

            Then("The observer receives an empty state and then completes")
            obs.awaitOnNext(1, timeout)
            obs.getOnNextEvents.get(0) shouldBe SingleValueKey(
                "first", None, NoOwnerId)
            obs.awaitCompletion(timeout)
            obs.getOnCompletedEvents should not be empty
        }

        scenario("Multi value key namespaces observable emits empty for null namespace") {
            Given("An object in storage")
            val obj = new State
            storage.create(obj)

            And("A null namespaces observable")
            val namespaces = Observable.just[String](null)

            When("An observer from the first namespace subscribes")
            val obs = new TestObserver[StateKey] with AwaitableObserver[StateKey]
            storage.keyObservable(namespaces, classOf[State], obj.id, "multi")
                .subscribe(obs)

            Then("The observer receives an empty state and then completes")
            obs.awaitOnNext(1, timeout)
            obs.getOnNextEvents.get(0) shouldBe MultiValueKey("multi", Set())
            obs.awaitCompletion(timeout)
            obs.getOnCompletedEvents should not be empty
        }

        scenario("Namespaces can add, read and delete different single first state")
        {
            testMultiNamespaceAddReadDeleteSingle("first")
        }

        scenario("Namespaces can add, read and delete different single last state") {
            testMultiNamespaceAddReadDeleteSingle("last")
        }

        scenario("Namespaces can add, read and delete different multi state") {
            Given("A second storage client")
            val namespaceId2 = UUID.randomUUID().toString

            And("An object in storage")
            val key = "multi"
            val obj = new State
            storage.create(obj)

            When("The first namespace adds a value")
            storage.addValue(classOf[State], obj.id, key, "1")
                .await(timeout) shouldBe StateResult(DefaultOwnerId)

            Then("The value is present for the first namespace")
            storage.getKey(classOf[State], obj.id, key)
                .await(timeout) shouldBe MultiValueKey(key, Set("1"))
            storage.getKey(namespaceId, classOf[State], obj.id, key)
                .await(timeout) shouldBe MultiValueKey(key, Set("1"))

            And("The second namespace can read the first namespace key")
            storage.getKey(namespaceId, classOf[State], obj.id, key)
                .await(timeout) shouldBe MultiValueKey(key, Set("1"))

            And("The value is not present for the second namespace")
            storage.getKey(namespaceId2, classOf[State], obj.id, key)
                .await(timeout) shouldBe MultiValueKey(key, Set())

            When("The second namespace adds a value")
            storage.addValueAs(namespaceId2, classOf[State], obj.id, key, "2")
                .await(timeout) shouldBe StateResult(DefaultOwnerId)

            Then("The key is unmodified for the first namespace")
            storage.getKey(classOf[State], obj.id, key)
                .await(timeout) shouldBe MultiValueKey(key, Set("1"))
            storage.getKey(namespaceId, classOf[State], obj.id, key)
                .await(timeout) shouldBe MultiValueKey(key, Set("1"))

            And("The value is present for the second namespace")
            storage.getKey(namespaceId2, classOf[State], obj.id, key)
                .await(timeout) shouldBe MultiValueKey(key, Set("2"))

            When("The first namespace removes the value")
            storage.removeValue(classOf[State], obj.id, key, "1")
                .await(timeout) shouldBe StateResult(DefaultOwnerId)

            Then("The value is not present for the first namespace")
            storage.getKey(classOf[State], obj.id, key)
                .await(timeout) shouldBe MultiValueKey(key, Set())
            storage.getKey(namespaceId, classOf[State], obj.id, key)
                .await(timeout) shouldBe MultiValueKey(key, Set())

            And("The value is unmodified for the second namespace")
            storage.getKey(namespaceId2, classOf[State], obj.id, key)
                .await(timeout) shouldBe MultiValueKey(key, Set("2"))

            When("The second namespace removes the value")
            storage.removeValueAs(namespaceId2, classOf[State], obj.id, key, "2")
                .await(timeout) shouldBe StateResult(DefaultOwnerId)

            Then("The value is removed for the second namespace")
            storage.getKey(namespaceId2, classOf[State], obj.id, key)
                .await(timeout) shouldBe MultiValueKey(key, Set())
        }

        scenario("Namespaces can monitor other namespace state for single value keys") {
            Given("A second storage client")
            val namespaceId2 = UUID.randomUUID().toString

            And("An object in storage")
            val obj = new State
            storage.create(obj)

            When("An observer from the first namespace subscribes")
            val obs1 = new TestObserver[StateKey] with AwaitableObserver[StateKey]
            storage.keyObservable(namespaceId, classOf[State], obj.id, "first")
                .subscribe(obs1)

            Then("The observer should receive a notification")
            obs1.awaitOnNext(1, timeout) shouldBe true
            obs1.getOnNextEvents.get(0) shouldBe SingleValueKey(
                "first", None, NoOwnerId)

            When("The second namespace adds a value")
            storage.addValueAs(namespaceId2, classOf[State], obj.id, "first", "2")
                .await(timeout) shouldBe StateResult(DefaultOwnerId)

            And("The first namespace adds a value")
            storage.addValue(classOf[State], obj.id, "first", "1")
                .await(timeout) shouldBe StateResult(DefaultOwnerId)

            Then("The observers should receive a notification for the first namespace")
            obs1.awaitOnNext(2, timeout) shouldBe true
            obs1.getOnNextEvents.get(1) shouldBe SingleValueKey(
                "first", Some("1"), DefaultOwnerId)

            When("The first namespace removes the value")
            storage.removeValue(classOf[State], obj.id, "first", null)
                .await(timeout) shouldBe StateResult(DefaultOwnerId)

            Then("The observer should receive a notification for the first namespace")
            obs1.awaitOnNext(3, timeout) shouldBe true
            obs1.getOnNextEvents.get(0) shouldBe SingleValueKey(
                "first", None, NoOwnerId)
        }

        scenario("Namespaces can monitor other namespace state for multi value keys") {
            Given("A second storage client")
            val namespaceId2 = UUID.randomUUID().toString

            And("An object in storage")
            val obj = new State
            storage.create(obj)

            When("An observer from the first namespace subscribes")
            val obs1 = new TestObserver[StateKey] with AwaitableObserver[StateKey]
            storage.keyObservable(namespaceId, classOf[State], obj.id, "multi")
                .subscribe(obs1)

            Then("The observer should receive a notification")
            obs1.awaitOnNext(1, timeout) shouldBe true
            obs1.getOnNextEvents.get(0) shouldBe MultiValueKey("multi", Set())

            When("The second namespace adds a value")
            storage.addValueAs(namespaceId2, classOf[State], obj.id, "multi", "2")
                .await(timeout) shouldBe StateResult(DefaultOwnerId)

            And("The first namespace adds a value")
            storage.addValue(classOf[State], obj.id, "multi", "1")
                .await(timeout) shouldBe StateResult(DefaultOwnerId)

            Then("The observer should receive a notification for the first namespace")
            obs1.awaitOnNext(2, timeout) shouldBe true
            obs1.getOnNextEvents.get(1) shouldBe
            MultiValueKey("multi", Set("1"))

            When("The first namespace removes the value")
            storage.removeValue(classOf[State], obj.id, "multi", "1")
                .await(timeout) shouldBe StateResult(DefaultOwnerId)

            Then("The observer should receive a notification for the first namespace")
            obs1.awaitOnNext(3, timeout) shouldBe true
            obs1.getOnNextEvents.get(2) shouldBe MultiValueKey("multi", Set())
        }

        scenario("Namespaces can switch between namespace state for single value keys") {
            Given("A second storage client")
            val namespaceId2 = UUID.randomUUID().toString

            And("An object in storage")
            val obj = new State
            storage.create(obj)

            And("A namespaces source subject")
            val namespaces = PublishSubject.create[String]

            When("An observer from the first namespace subscribes")
            val obs = new
                    TestObserver[StateKey] with AwaitableObserver[StateKey]
            storage.keyObservable(namespaces, classOf[State], obj.id, "first")
                .subscribe(obs)

            And("The subject emits the first namespace")
            namespaces onNext namespaceId

            Then("The observer should receive an empty value")
            obs.awaitOnNext(1, timeout)
            obs.getOnNextEvents.get(0) shouldBe SingleValueKey(
                "first", None, NoOwnerId)

            When("The subject emits the second namespace")
            namespaces onNext namespaceId2

            Then("The observer should receive an empty value")
            obs.awaitOnNext(2, timeout)
            obs.getOnNextEvents.get(1) shouldBe SingleValueKey(
                "first", None, NoOwnerId)

            When("The first namespace adds a value")
            storage.addValue(classOf[State], obj.id, "first", "1")
                .await(timeout) shouldBe StateResult(DefaultOwnerId)

            And("The second namespace adds a value")
            storage.addValueAs(namespaceId2, classOf[State], obj.id, "first", "2")
                .await(timeout) shouldBe StateResult(DefaultOwnerId)

            Then("The observer should receive the value from the second namespace")
            obs.awaitOnNext(3, timeout)
            obs.getOnNextEvents.get(2) shouldBe SingleValueKey(
                "first", Some("2"), DefaultOwnerId)

            When("The subject emits the first namespace")
            namespaces onNext namespaceId

            Then("The observer should receive the value from the first namespace")
            obs.awaitOnNext(4, timeout)
            obs.getOnNextEvents.get(3) shouldBe SingleValueKey(
                "first", Some("1"), DefaultOwnerId)

            When("The second namespace adds a value")
            storage.addValueAs(namespaceId2, classOf[State], obj.id, "first", "3")
                .await(timeout) shouldBe StateResult(DefaultOwnerId)

            And("The first namespace removes the value")
            storage.removeValue(classOf[State], obj.id, "first", null)
                .await(timeout) shouldBe StateResult(DefaultOwnerId)

            Then("The observer should receive an empty value")
            obs.awaitOnNext(5, timeout)
            obs.getOnNextEvents.get(4) shouldBe SingleValueKey(
                "first", None, NoOwnerId)

            When("The subject emits the second namespace")
            namespaces onNext namespaceId2

            Then("The observer should receive the value from the second namespace")
            obs.awaitOnNext(6, timeout)
            obs.getOnNextEvents.get(5) shouldBe SingleValueKey(
                "first", Some("3"), DefaultOwnerId)

            When("The second namespace removes the value")
            storage.removeValueAs(namespaceId2, classOf[State], obj.id, "first", null)
                .await(timeout) shouldBe StateResult(DefaultOwnerId)


            Then("The observer should receive an empty value")
            obs.awaitOnNext(7, timeout)
            obs.getOnNextEvents.get(6) shouldBe SingleValueKey(
                "first", None, NoOwnerId)
        }

        scenario("Namespaces can switch between namespace state for multi value keys") {
            Given("A second storage client")
            val namespaceId2 = UUID.randomUUID().toString

            And("An object in storage")
            val obj = new State
            storage.create(obj)

            And("A namespaces source subject")
            val namespaces = PublishSubject.create[String]

            When("An observer from the first namespace subscribes")
            val obs = new
                    TestObserver[StateKey] with AwaitableObserver[StateKey]
            storage.keyObservable(namespaces, classOf[State], obj.id, "multi")
                .subscribe(obs)

            And("The subject emits the first namespace")
            namespaces onNext namespaceId

            Then("The observer should receive an empty value")
            obs.awaitOnNext(1, timeout)
            obs.getOnNextEvents.get(0) shouldBe MultiValueKey("multi", Set())

            When("The subject emits the second namespace")
            namespaces onNext namespaceId2

            Then("The observer should receive an empty value")
            obs.awaitOnNext(2, timeout)
            obs.getOnNextEvents.get(1) shouldBe MultiValueKey("multi", Set())

            When("The first namespace adds a value")
            storage.addValue(classOf[State], obj.id, "multi", "1")
                .await(timeout) shouldBe StateResult(DefaultOwnerId)

            And("The second namespace adds a value")
            storage.addValueAs(namespaceId2, classOf[State], obj.id, "multi", "2")
                .await(timeout) shouldBe StateResult(DefaultOwnerId)

            Then("The observer should receive the value from the second namespace")
            obs.awaitOnNext(3, timeout)
            obs.getOnNextEvents.get(2) shouldBe MultiValueKey("multi", Set("2"))

            When("The subject emits the first namespace")
            namespaces onNext namespaceId

            Then("The observer should receive the value from the first namespace")
            obs.awaitOnNext(4, timeout)
            obs.getOnNextEvents.get(3) shouldBe MultiValueKey("multi", Set("1"))

            When("The second namespace adds a value")
            storage.addValueAs(namespaceId2, classOf[State], obj.id, "multi", "3")
                .await(timeout) shouldBe StateResult(DefaultOwnerId)

            And("The first namespace removes the value")
            storage.removeValue(classOf[State], obj.id, "multi", "1")
                .await(timeout) shouldBe StateResult(DefaultOwnerId)

            Then("The observer should receive an empty value")
            obs.awaitOnNext(5, timeout)
            obs.getOnNextEvents.get(4) shouldBe MultiValueKey("multi", Set())

            When("The subject emits the second namespace")
            namespaces onNext namespaceId2

            Then("The observer should receive the value from the second namespace")
            obs.awaitOnNext(6, timeout)
            obs.getOnNextEvents.get(5) shouldBe
            MultiValueKey("multi", Set("2", "3"))

            When("The second namespace removes the value")
            storage.removeValueAs(namespaceId2, classOf[State], obj.id, "multi", "2")
                .await(timeout) shouldBe StateResult(DefaultOwnerId)

            Then("The observer should receive an empty value")
            obs.awaitOnNext(7, timeout)
            obs.getOnNextEvents.get(6) shouldBe MultiValueKey("multi", Set("3"))
        }
    }

    feature("Test global state tables") {
        scenario("Global table getter works") {
            Given("A storage")

            And("A path for global table 'global-table'")
            val path = storage.tablePath("global-table")

            Then("getTable() throws nothing when called with correct arguments")

            val t = storage.getTable[Int, String]("global-table")
            t.isInstanceOf[ScoreStateTable] shouldBe true

            intercept[IllegalArgumentException] {
                val t = storage.getTable[Int, String]("unknown-global-table")
            }

            val t1 = storage.getTable("global-table")(classTag[Int],
                                                     classTag[String])
            t1.isInstanceOf[ScoreStateTable] shouldBe true

            intercept[IllegalArgumentException] {
                val t = storage.getTable("global-table")(classTag[Double],
                                                         classTag[String])
            }

            intercept[IllegalArgumentException] {
                val t = storage.getTable("global-table")(classTag[Int],
                                                         classTag[UUID])
            }
        }
    }
}
