/*
 * Copyright 2014 Ben Manes. All Rights Reserved.
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
package com.github.benmanes.caffeine;

import static java.util.Objects.requireNonNull;

import java.io.InvalidObjectException;
import java.io.ObjectInputStream;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Iterator;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Queue;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.stream.Collectors;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.github.benmanes.caffeine.SingleConsumerQueue.Node;
import com.github.benmanes.caffeine.base.UnsafeAccess;

/**
 * A lock-free unbounded queue based on linked nodes that supports concurrent producers and is
 * restricted to a single consumer. This queue orders elements FIFO (first-in-first-out). The
 * <em>head</em> of the queue is that element that has been on the queue the longest time. The
 * <em>tail</em> of the queue is that element that has been on the queue the shortest time. New
 * elements are inserted at the tail of the queue, and the queue retrieval operations obtain
 * elements at the head of the queue. Like most other concurrent collection implementations, this
 * class does not permit the use of {@code null} elements.
 * <p>
 * A {@code SingleConsumerQueue} is an appropriate choice when many producer threads will share
 * access to a common collection and a single consumer thread drains it. This collection is useful
 * in scenarios such as implementing flat combining, actors, or lock amortization.
 * <p>
 * This implementation employs combination to transfer elements between threads that are producing
 * concurrently. This approach avoids contention on the queue by combining colliding operations
 * that have identical semantics. When a pair of producers collide, the task of performing the
 * combined set of operations is delegated to one of the threads and the other thread optionally
 * waits for its operation to be performed. This decision of whether to wait for completion is
 * determined by constructing either a <em>linearizable</em> or <em>optimistic</em> queue.
 * <p>
 * Iterators are <i>weakly consistent</i>, returning elements reflecting the state of the queue at
 * some point at or since the creation of the iterator. They do <em>not</em> throw {@link
 * java.util.ConcurrentModificationException}, and may proceed concurrently with other operations.
 * Elements contained in the queue since the creation of the iterator will be returned exactly once.
 * <p>
 * Beware that it is the responsibility of the caller to ensure that a consumer has exclusive read
 * access to the queue. This implementation does <em>not</em> include fail-fast behavior to guard
 * against incorrect consumer usage.
 * <p>
 * Beware that, unlike in most collections, the {@code size} method is <em>NOT</em> a
 * constant-time operation. Because of the asynchronous nature of these queues, determining the
 * current number of elements requires a traversal of the elements, and so may report inaccurate
 * results if this collection is modified during traversal.
 *
 * @author ben.manes@gmail.com (Ben Manes)
 * @param <E> the type of elements held in this collection
 */
@Beta
public final class SingleConsumerQueue<E> extends HeadAndTailRef<E>
    implements Queue<E>, Serializable {

  /*
   * The queue is represented as a singly-linked list with an atomic head and tail reference. It is
   * based on the non-intrusive multi-producer / single-consumer node queue described by
   * Dmitriy Vyukov [1].
   *
   * The backoff strategy of combining operations with identical semantics is based on inverting
   * the elimination technique [2]. Elimination allows pairs of operations with reverse semantics,
   * like pushes and pops on a stack, to complete without any central coordination and therefore
   * substantially aids scalability. The approach of applying elimination and reversing its
   * semantics was explored in [3, 4].
   *
   * This implementation borrows optimizations from {@link java.util.concurrent.Exchanger} for
   * choosing an arena location and awaiting a match [5].
   *
   * [1] Non-intrusive MPSC node-based queue
   * http://www.1024cores.net/home/lock-free-algorithms/queues/non-intrusive-mpsc-node-based-queue
   * [2] A Scalable Lock-free Stack Algorithm
   * http://citeseerx.ist.psu.edu/viewdoc/summary?doi=10.1.1.156.8728
   * [3] Using elimination to implement scalable and lock-free fifo queues
   * http://citeseerx.ist.psu.edu/viewdoc/summary?doi=10.1.1.108.6422
   * [4] A Dynamic Elimination-Combining Stack Algorithm
   * http://www.cs.bgu.ac.il/~hendlerd/papers/DECS.pdf
   * [5] A Scalable Elimination-based Exchange Channel
   * http://citeseerx.ist.psu.edu/viewdoc/summary?doi=10.1.1.59.7396
   */

  /** The number of CPUs */
  static final int NCPU = Runtime.getRuntime().availableProcessors();

  /** The number of slots in the elimination array. */
  static final int ARENA_LENGTH = ceilingNextPowerOfTwo((NCPU + 1) / 2);

  /** The mask value for indexing into the arena. */
  static final int ARENA_MASK = ARENA_LENGTH - 1;

  /**
   * The number of times to spin (doing nothing except polling a memory location) before giving up
   * while waiting to eliminate an operation. Should be zero on uniprocessors. On multiprocessors,
   * this value should be large enough so that two threads exchanging items as fast as possible
   * block only when one of them is stalled (due to GC or preemption), but not much longer, to avoid
   * wasting CPU resources. Seen differently, this value is a little over half the number of cycles
   * of an average context switch time on most systems. The value here is approximately the average
   * of those across a range of tested systems.
   */
  static final int SPINS = (NCPU == 1) ? 0 : 2000;

  /** The offset to the thread-specific probe field. */
  static final long PROBE = UnsafeAccess.objectFieldOffset(Thread.class, "threadLocalRandomProbe");

  static int ceilingNextPowerOfTwo(int x) {
    // From Hacker's Delight, Chapter 3, Harry S. Warren Jr.
    return 1 << (Integer.SIZE - Integer.numberOfLeadingZeros(x - 1));
  }

  /** Returns the arena index for the current thread. */
  static final int index() {
    int probe = UnsafeAccess.UNSAFE.getInt(Thread.currentThread(), PROBE);
    if (probe == 0) {
      ThreadLocalRandom.current(); // force initialization
      probe = UnsafeAccess.UNSAFE.getInt(Thread.currentThread(), PROBE);
    }
    return (probe & ARENA_MASK);
  }

  final AtomicReference<Node<E>>[] arena;
  final Function<E, Node<E>> factory;

  @SuppressWarnings({"unchecked", "rawtypes"})
  private SingleConsumerQueue(Function<E, Node<E>> factory) {
    arena = new AtomicReference[ARENA_LENGTH];
    for (int i = 0; i < ARENA_LENGTH; i++) {
      arena[i] = new AtomicReference<>();
    }
    Node<E> node = new Node<E>(null);
    this.factory = factory;
    lazySetHead(node);
    lazySetTail(node);
  }

  /**
   * Creates a queue that with an optimistic backoff strategy. A thread completes its operation
   * without waiting after it successfully hands off the additional element(s) to another producing
   * thread for batch insertion.
   *
   * @param <E> the type of elements held in this collection
   * @return a new queue where producers complete their operation immediately if combined with
   *         another producing thread's
   */
  public static <E> SingleConsumerQueue<E> optimistic() {
    return new SingleConsumerQueue<>(Node<E>::new);
  }

  /**
   * Creates a queue that with a linearizable backoff strategy. A thread waits for a completion
   * signal if it successfully hands off the additional element(s) to another producing
   * thread for batch insertion.
   *
   * @param <E> the type of elements held in this collection
   * @return a new queue where producers wait for a completion signal after combining its addition
   *         with another producing thread's
   */
  public static <E> SingleConsumerQueue<E> linearizable() {
    return new SingleConsumerQueue<>(LinearizableNode<E>::new);
  }

  @Override
  public boolean isEmpty() {
    return (head == tail);
  }

  @Override
  public int size() {
    Node<E> h = head;
    Node<E> cursor = h.getNextRelaxed();
    int size = 0;
    while (cursor != null) {
      cursor = cursor.getNextRelaxed();
      size++;
    }
    return size;
  }

  @Override
  public void clear() {
    lazySetHead(tail);
  }

  @Override
  public boolean contains(@Nullable Object o) {
    if (o == null) {
      return false;
    }

    Node<E> cursor = head.getNextRelaxed();
    while (cursor != null) {
      if (o.equals(cursor.value)) {
        return true;
      }
      cursor = cursor.next;
    }
    return false;
  }

  @Override
  public boolean containsAll(Collection<?> c) {
    Objects.requireNonNull(c);
    for (Object e : c) {
      if (!contains(e)) {
        return false;
      }
    }
    return true;
  }

  @Override
  public E peek() {
    Node<E> next = head.getNextRelaxed();
    return (next == null) ? null : next.value;
  }

  @Override
  public E element() {
    E e = peek();
    if (e == null) {
      throw new NoSuchElementException();
    }
    return e;
  }

  @Override
  public boolean offer(E e) {
    Objects.requireNonNull(e);

    Node<E> node = factory.apply(e);
    append(node, node);
    return true;
  }

  @Override
  public E poll() {
    Node<E> next = head.getNextRelaxed();
    if (next == null) {
      return null;
    }
    lazySetHead(next);
    E e = next.value;
    next.value = null;
    return e;
  }

  @Override
  public boolean add(E e) {
    return offer(e);
  }

  @Override
  public boolean addAll(Collection<? extends E> c) {
    Objects.requireNonNull(c);

    Node<E> first = null;
    Node<E> last = null;
    for (E e : c) {
      requireNonNull(e);
      if (first == null) {
        first = factory.apply(e);
        last = first;
      } else {
        Node<E> newLast = factory.apply(e);
        last.lazySetNext(newLast);
        last = newLast;
      }
    }
    if (first == null) {
      return false;
    }
    append(first, last);
    return true;
  }

  /** Adds the linked list of nodes to the queue. */
  void append(Node<E> first, Node<E> last) {
    for (;;) {
      Node<E> t = tail;
      if (casTail(t, last)) {
        t.next = first;
        for (;;) {
          first.complete();
          if (first == last) {
            return;
          }
          first = first.getNextRelaxed();
        }
      }
      Node<E> node = transferOrCombine(first, last);
      if (node == null) {
        first.await();
        return;
      } else if (node != first) {
        last = node;
      }
    }
  }

  /**
   * Attempts to transfer the linked list to a waiting consumer or receive a linked list from a
   * waiting producer.
   *
   * @param first the first node in the linked list to try to transfer
   * @param last the last node in the linked list to try to transfer
   * @return either {@code null} if the element was transferred, the first node if neither a
   *         transfer or receive were successful, or the received last element from a producer
   */
  @Nullable Node<E> transferOrCombine(@Nonnull Node<E> first, Node<E> last) {
    int index = index();
    AtomicReference<Node<E>> slot = arena[index];

    for (;;) {
      Node<E> found = slot.get();
      if (found == null) {
        if (slot.compareAndSet(null, first)) {
          for (int spin = 0; spin < SPINS; spin++) {
            if (slot.get() != first) {
              return null;
            }
          }
          return slot.compareAndSet(first, null) ? first : null;
        }
      } else if (slot.compareAndSet(found, null)) {
        last.lazySetNext(found);
        last = findLast(found);
        for (int i = 1; i < ARENA_LENGTH; i++) {
          slot = arena[(i + index) & ARENA_MASK];
          found = slot.get();
          if ((found != null) && slot.compareAndSet(found, null)) {
            last.lazySetNext(found);
            last = findLast(found);
          }
        }
        return last;
      }
    }
  }

  /** Returns the last node in the linked list. */
  static <E> Node<E> findLast(Node<E> node) {
    Node<E> next;
    while ((next = node.getNextRelaxed()) != null) {
      node = next;
    }
    return node;
  }

  @Override
  public E remove() {
    E e = poll();
    if (e == null) {
      throw new NoSuchElementException();
    }
    return e;
  }

  @Override
  public boolean remove(Object o) {
    Objects.requireNonNull(o);

    Node<E> t = tail;
    Node<E> prev = getHeadRelaxed();
    Node<E> cursor = prev.getNextRelaxed();
    while (cursor != null) {
      Node<E> next = cursor.getNextRelaxed();
      if (o.equals(cursor.value)) {
        if ((t == cursor) && !casTail(t, prev) && (next == null)) {
          next = t.next;
        }
        prev.lazySetNext(next);
        return true;
      }
      prev = cursor;
      cursor = next;
    }
    return false;
  }

  @Override
  public boolean removeAll(Collection<?> c) {
    return removeByPresentce(c, false);
  }

  @Override
  public boolean retainAll(Collection<?> c) {
    return removeByPresentce(c, true);
  }

  /**
   * Removes elements based on whether they are also present in the provided collection.
   *
   * @param c collection containing elements to keep or discard
   * @param retain whether to retain only or remove only elements present in both collections
   */
  boolean removeByPresentce(Collection<?> c, boolean retain) {
    Objects.requireNonNull(c);

    Node<E> t = tail;
    Node<E> prev = getHeadRelaxed();
    Node<E> cursor = prev.getNextRelaxed();
    boolean modified = false;
    while (cursor != null) {
      boolean present = c.contains(cursor.value);
      Node<E> next = cursor.getNextRelaxed();
      if (present != retain) {
        if ((t == cursor) && !casTail(t, prev) && (next == null)) {
          next = t.next;
        }
        prev.lazySetNext(next);
        modified = true;
      } else {
        prev = cursor;
      }
      cursor = prev.getNextRelaxed();
    }
    return modified;
  }

  @Override
  public Iterator<E> iterator() {
    return new Iterator<E>() {
      Node<E> t = tail;
      Node<E> prev = null;
      Node<E> cursor = getHeadRelaxed();
      boolean failOnRemoval = true;

      @Override
      public boolean hasNext() {
        return (cursor != t);
      }

      @Override
      public E next() {
        if (!hasNext()) {
          throw new NoSuchElementException();
        }
        advance();
        failOnRemoval = false;
        return cursor.value;
      }

      private void advance() {
        if ((prev == null) || !failOnRemoval) {
          prev = cursor;
        }
        cursor = cursor.getNextRelaxed();
      }

      @Override
      public void remove() {
        if (failOnRemoval) {
          throw new IllegalStateException();
        }
        if ((t == cursor) && !casTail(t, prev) && (cursor.getNextRelaxed() == null)) {
          prev.lazySetNext(t.next);
        } else {
          prev.lazySetNext(cursor.getNextRelaxed());
        }
        failOnRemoval = true;
      }
    };
  }

  @Override
  public Object[] toArray() {
    return stream().toArray();
  }

  @Override
  public <T> T[] toArray(T[] a) {
    return stream().collect(Collectors.toList()).toArray(a);
  }

  @Override
  public String toString() {
    return stream().map(Object::toString).collect(Collectors.joining(", ", "[", "]"));
  }

  /* ---------------- Serialization Support -------------- */

  static final long serialVersionUID = 1;

  Object writeReplace() {
    return new SerializationProxy<E>(this);
  }

  private void readObject(ObjectInputStream stream) throws InvalidObjectException {
    throw new InvalidObjectException("Proxy required");
  }

  /** A proxy that is serialized instead of the queue. */
  static final class SerializationProxy<E> implements Serializable {
    final boolean linearizable;
    final List<E> list;

    SerializationProxy(SingleConsumerQueue<E> queue) {
      linearizable = (queue.factory.apply(null) instanceof LinearizableNode<?>);
      list = new ArrayList<>(queue);
    }

    Object readResolve() {
      SingleConsumerQueue<E> queue = linearizable ? linearizable() : optimistic();
      queue.addAll(list);
      return queue;
    }

    static final long serialVersionUID = 1;
  }

  static class Node<E> {
    final static long NEXT_OFFSET = UnsafeAccess.objectFieldOffset(Node.class, "next");

    E value;
    volatile Node<E> next;

    Node(@Nullable E value) {
      this.value = value;
    }

    @SuppressWarnings("unchecked")
    @Nullable Node<E> getNextRelaxed() {
      return (Node<E>) UnsafeAccess.UNSAFE.getObject(this, NEXT_OFFSET);
    }

    void lazySetNext(@Nullable Node<E> newNext) {
      UnsafeAccess.UNSAFE.putOrderedObject(this, NEXT_OFFSET, newNext);
    }

    /** A no-op notification that the element was added to the queue. */
    void complete() {}

    /** A no-op wait until the operation has completed. */
    void await() {}

    /** Always returns that the operation completed. */
    boolean isDone() {
      return true;
    }

    @Override
    public String toString() {
      return getClass().getSimpleName() + "[" + value + "]";
    }
  }

  static final class LinearizableNode<E> extends Node<E> {
    volatile boolean done;

    LinearizableNode(@Nullable E value) {
      super(value);
    }

    /** A notification that the element was added to the queue. */
    @Override
    void complete() {
      done = true;
    }

    /** A busy wait until the operation has completed. */
    @Override
    void await() {
      while (!done) {};
    }

    /** Returns whether the operation completed. */
    @Override
    boolean isDone() {
      return done;
    }
  }
}

abstract class PadHead {
  long p00, p01, p02, p03, p04, p05, p06, p07;
  long p30, p31, p32, p33, p34, p35, p36, p37;
}

/** Enforces a memory layout to avoid false sharing by padding the head node. */
abstract class HeadRef<E> extends PadHead {
  static final long HEAD_OFFSET = UnsafeAccess.objectFieldOffset(HeadRef.class, "head");

  volatile Node<E> head;

  @SuppressWarnings("unchecked")
  Node<E> getHeadRelaxed() {
    return (Node<E>) UnsafeAccess.UNSAFE.getObject(this, HEAD_OFFSET);
  }

  void lazySetHead(Node<E> next) {
    UnsafeAccess.UNSAFE.putOrderedObject(this, HEAD_OFFSET, next);
  }
}

abstract class PadTail<E> extends HeadRef<E> {
  long p00, p01, p02, p03, p04, p05, p06, p07;
  long p30, p31, p32, p33, p34, p35, p36, p37;
}

/** Enforces a memory layout to avoid false sharing by padding the tail node. */
abstract class HeadAndTailRef<E> extends PadTail<E> {
  static final long TAIL_OFFSET = UnsafeAccess.objectFieldOffset(HeadAndTailRef.class, "tail");

  volatile Node<E> tail;

  void lazySetTail(Node<E> next) {
    UnsafeAccess.UNSAFE.putOrderedObject(this, TAIL_OFFSET, next);
  }

  boolean casTail(Node<E> expect, Node<E> update) {
    return UnsafeAccess.UNSAFE.compareAndSwapObject(this, TAIL_OFFSET, expect, update);
  }
}
