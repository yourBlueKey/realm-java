/*
 * Copyright 2014 Realm Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.realm;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;

import io.realm.annotations.RealmClass;
import io.realm.internal.Row;
import io.realm.internal.InvalidRow;
import io.realm.internal.Table;
import io.realm.internal.TableQuery;
import io.realm.internal.UncheckedRow;

/**
 * In Realm you define your model classes by sub-classing RealmObject and adding fields to be
 * persisted. You then create your objects within a Realm, and use your custom subclasses instead
 * of using the RealmObject class directly.
 * <p>
 * An annotation processor will create a proxy class for your RealmObject subclass. The getters and
 * setters should not contain any custom code of logic as they are overridden as part of the annotation
 * process.
 * <p>
 * A RealmObject is currently limited to the following:
 *
 * <ul>
 *   <li>Private fields.</li>
 *   <li>Getter and setters for these fields.</li>
 *   <li>Static methods.</li>
 * </ul>
 * <p>
 * The following field data types are supported (no boxed types):
 * <ul>
 *   <li>boolean</li>
 *   <li>short</li>
 *   <li>int</li>
 *   <li>long</li>
 *   <li>float</li>
 *   <li>double</li>
 *   <li>byte[]</li>
 *   <li>String</li>
 *   <li>Date</li>
 *   <li>Any RealmObject subclass</li>
 *   <li>RealmList</li>
 * </ul>
 * <p>
 * The types <code>short</code>, <code>int</code>, and <code>long</code> are mapped to <code>long</code>
 * when storing within a Realm.
 * <p>
 * Getter and setter names must have the name {@code getXXX} or {@code setXXX} if
 * the field name is {@code XXX}. Getters for fields of type boolean can be called {@code isXXX} as
 * well. Fields with a m-prefix must have getters and setters named setmXXX and getmXXX which is
 * the default behavior when Android Studio automatically generates the getters and setters.
 * <p>
 * Fields annotated with {@link io.realm.annotations.Ignore} don't have these restrictions and
 * don't require either a getter or setter.
 * <p>
 * Realm will create indexes for fields annotated with {@link io.realm.annotations.Index}. This
 * will speedup queries but will have a negative impact on inserts and updates.
 * * <p>
 * A RealmObject cannot be passed between different threads.
 *
 * @see Realm#createObject(Class)
 * @see Realm#copyToRealm(RealmObject)
 */

@RealmClass
public abstract class RealmObject {

    protected Row row;
    protected Realm realm;

    /**
     * Removes the object from the Realm it is currently associated to.
     * <p>
     * After this method is called the object will be invalid and any operation (read or write)
     * performed on it will fail with an IllegalStateException
     */
    public void removeFromRealm() {
        if (row == null) {
            throw new IllegalStateException("Object malformed: missing object in Realm. Make sure to instantiate RealmObjects with Realm.createObject()");
        }
        if (realm == null) {
            throw new IllegalStateException("Object malformed: missing Realm. Make sure to instantiate RealmObjects with Realm.createObject()");
        }
        row.getTable().moveLastOver(row.getIndex());
        row = InvalidRow.INSTANCE;
    }

    /**
     * Check if the RealmObject is still valid to use ie. the RealmObject hasn't been deleted nor
     * has the {@link io.realm.Realm} been closed. It will always return false for stand alone
     * objects.
     *
     * @return {@code true} if the object is still accessible, {@code false} otherwise or if it is a
     * standalone object.
     */
    public boolean isValid() {
        return row != null && row.isAttached();
    }

    /**
     * Returns the Realm instance this object belongs to. Internal use only.
     *
     * @return The Realm this object belongs to or {@code null} if it is a standalone object.
     */
    protected static Realm getRealm(RealmObject obj) {
        return obj.realm;
    }

    /**
     * Returns the {@link Row} representing this object. Internal use only.
     *
     * @return The {@link Row} this object belongs to or {@code null} if it is a standalone object.
     */
    protected static Row getRow(RealmObject obj) {
        return obj.row;
    }

    /**
     * Encapsulates an async {@link RealmQuery}.
     * <p>
     * This will run the {@link RealmQuery} on a worker thread, then invoke this callback on the caller thread
     */
    public interface QueryCallback<E extends RealmObject> {
        void onSuccess (E result);
        void onError (Exception t);
    }

    /**
     * Used for debugging/testing purpose to add any logic (within the caller's thread)
     * before we return the results
     */
    interface DebugRealmObjectQueryCallback<E extends RealmObject> extends RealmObject.QueryCallback<E> {
        /**
         * Runs on the caller's thread just before we hand over the result to {@link #onSuccess(RealmObject)}
         */
        void onBackgroundQueryCompleted(Realm realm);
    }

    // TODO Async stuff (move as interface)
    private Future<Long> pendingQuery;
    private boolean isCompleted = false;
    private Class<? extends RealmObject> clazz;

    void setPendingQuery (Future<Long> pendingQuery) {
        this.pendingQuery = pendingQuery;
        if (isLoaded()) { // The query completed before RealmQuery
            // had a chance to call setPendingQuery to register the pendingQuery (used btw
            // to determine isLoaded behaviour)
            onCompleted();

        } else {
            // This will be handled by the Realm#handler
            // we need a handler since the onCompleted should run on the caller
            // thread
            // register a listener to wait for the worker thread to finish
            // so we can call onCompleted
//            pendingQuery.addListener(new O)
        }
    }

    void setType (Class<? extends RealmObject> clazz) {
        this.clazz = clazz;
    }

    public boolean isLoaded () {
        realm.checkIfValid();
        // sync query
        return pendingQuery == null || isCompleted;
    }

    // Doesn't guarantee to import correctly the result (because the user may have advanced)
    public void load() {
        realm.checkIfValid();
        //TODO the query could be complete but the handler didn't receive the results yet
        //     same behaviour for RealmResults
        if (pendingQuery != null && !pendingQuery.isDone()) {
            onCompleted();
        }
    }

    // should be invoked once the pendingQuery finish
    void onCompleted () {
        realm.checkIfValid();
        try {
            long nativeRowHandoverPointer = pendingQuery.get();// make the query blocking
            onCompleted(nativeRowHandoverPointer);
        } catch (InterruptedException e) {
            e.printStackTrace();
        } catch (ExecutionException e) {
            e.printStackTrace();
        }
    }

    void onCompleted (long nativeRowHandoverPointer) {
        realm.checkIfValid();
        long nativeRowPointer = TableQuery.nativeImportHandoverRowIntoSharedGroup(nativeRowHandoverPointer, realm.getSharedGroupPointer());
        Table table = realm.getTable(clazz);
        this.row = table.getUncheckedRowByPointer(nativeRowPointer);

//           = query.importHandoverRow(rowHandoverPointer);
//            //TODO call method to import handover
//            // this may fail with BadVersionException in this case we keep the RealmResuls empty
//            // then will wait for REALM_COMPLETED_ASYNC_QUERY fired anyway, which handle more complex
//            // use cases like retry, ignore etc
//            table = query.importHandoverTableView(tvHandover, realm.sharedGroup.getNativePointer());
        isCompleted = true;
        notifyChangeListeners();
    }

    List<RealmChangeListener> listeners = new CopyOnWriteArrayList<RealmChangeListener>();
    // the RealmChangeListener needs to be in the same thread
    // otherwise ask for a handler to post results
    public void addChangeListener(RealmChangeListener listener) {
        if (listener == null) {
            throw new IllegalArgumentException("Listener should not be null");
        }
        realm.checkIfValid();

        if (!listeners.contains(listener)) {
            listeners.add(listener);
        }


    }

   public void deleteChangeListener(RealmChangeListener listener) {
        if (listener == null) {
            throw new IllegalArgumentException("Listener should not be null");
        }
        realm.checkIfValid();

        listeners.remove(listener);
    }

   public void notifyChangeListeners() {
        realm.checkIfValid();
        for (RealmChangeListener listener: listeners) {
            listener.onChange();
        }
    }

    public void deleteChangeListeners() {
        realm.checkIfValid();
        listeners.clear();
    }
}
