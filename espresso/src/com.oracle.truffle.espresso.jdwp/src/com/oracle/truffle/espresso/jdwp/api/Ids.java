/*
 * Copyright (c) 2019, 2025, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 * Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
 * or visit www.oracle.com if you need additional information or have any
 * questions.
 */
package com.oracle.truffle.espresso.jdwp.api;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.oracle.truffle.api.TruffleLogger;

/**
 * Class that keeps an ID representation of all entities when communicating with a debugger through
 * JDWP. Each entity will be assigned a unique ID. Only weak references are kept for entities.
 */
public final class Ids<T> {
    public static final int ID_SIZE = 8;
    private static final Pattern ANON_INNER_CLASS_PATTERN = Pattern.compile(".*\\$\\d+.*");

    private volatile long uniqueID = 1;

    /**
     * All entities stored while communicating with the debugger. The array will be expanded
     * whenever an ID for a new entity is requested.
     */
    private WeakReference<T>[] objects;

    /**
     * A special object representing the null value. This object must be passed on by the
     * implementing language.
     */
    private final T nullObject;

    private HashMap<String, Long> innerClassIDMap = new HashMap<>(16);

    private List<Object> pinnedObjects = new ArrayList<>();

    private volatile boolean pinningState = false;
    private TruffleLogger logger;

    @SuppressWarnings({"unchecked", "rawtypes"})
    public Ids(T nullObject) {
        this.nullObject = nullObject;
        objects = new WeakReference[]{new WeakReference<>(nullObject)};
    }

    /**
     * Returns the unique ID representing the input object.
     *
     * @param object
     * @return the ID of the object
     */
    public long getIdAsLong(T object) {
        if (object == null) {
            log(() -> "Null object when getting ID");
            return 0;
        }
        // lookup in cache
        for (int i = 1; i < objects.length; i++) {
            // really slow lookup path
            if (objects[i].get() == object) {
                final int index = i;
                log(() -> "ID cache hit for object: " + object + " with ID: " + index);
                return i;
            }
        }
        // check the anonymous inner class map
        if (object instanceof KlassRef klass) {
            Long id = innerClassIDMap.get(klass.getNameAsString());
            if (id != null) {
                // inject new klass under existing ID
                objects[((int) (long) id)] = new WeakReference<>(object);
                return id;
            }
        }

        // cache miss, so generate a new ID
        return generateUniqueId(object);
    }

    /**
     * Returns the unique ID representing the input object or -1 if it's not registered.
     *
     * @param object
     * @return the ID of the object
     */
    public long getId(Object object) {
        // lookup in cache
        for (int i = 1; i < objects.length; i++) {
            // really slow lookup path
            if (objects[i].get() == object) {
                final int index = i;
                log(() -> "ID cache hit for object: " + object + " with ID: " + index);
                return i;
            }
        }
        return -1;
    }

    /**
     * Returns the object that is stored under the input ID.
     *
     * @param id the ID assigned to a object by {@code getIdAsLong()}
     * @return the object stored under the ID
     */
    public T fromId(int id) {
        if (id == 0) {
            log(() -> "Null object from ID: " + id);
            return nullObject;
        }
        if (id > objects.length) {
            log(() -> "Unknown object ID: " + id);
            return null;
        }
        WeakReference<T> ref = objects[id];
        T o = ref.get();
        if (o == null) {
            log(() -> "object with ID: " + id + " was garbage collected");
            return null;
        } else {
            log(() -> "returning object: " + o + " for ID: " + id);
            return o;
        }
    }

    /*
     * Generate a unique ID for a given object. Expand the underlying array and insert the object at
     * the last index in the new array.
     */
    private synchronized long generateUniqueId(T object) {
        long id = uniqueID++;
        assert objects.length == id;

        WeakReference<T>[] expandedArray = Arrays.copyOf(objects, objects.length + 1);
        expandedArray[objects.length] = new WeakReference<>(object);
        objects = expandedArray;
        log(() -> "Generating new ID: " + id + " for object: " + object);
        if (object instanceof KlassRef klass) {
            Matcher matcher = ANON_INNER_CLASS_PATTERN.matcher(klass.getNameAsString());
            if (matcher.matches()) {
                innerClassIDMap.put(klass.getNameAsString(), id);
            }
        }
        // pin object when VM in suspended state
        if (pinningState) {
            pinnedObjects.add(object);
        }
        return id;
    }

    public boolean checkRemoved(long refTypeId) {
        return innerClassIDMap.containsValue(refTypeId);
    }

    private void log(Supplier<String> supplier) {
        logger.finest(supplier);
    }

    public synchronized void pinAll() {
        pinningState = true;
        for (WeakReference<T> object : objects) {
            Object toPin = object.get();
            if (toPin != null) {
                pinnedObjects.add(toPin);
            }
        }
    }

    public synchronized void unpinAll() {
        pinningState = false;
        pinnedObjects.clear();
    }

    public void injectLogger(TruffleLogger truffleLogger) {
        this.logger = truffleLogger;
    }
}