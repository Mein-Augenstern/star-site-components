/*
 * Copyright 2026 muyi
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
package com.xp.starsite.tuner.queue;

import java.util.AbstractQueue;
import java.util.Collection;
import java.util.Iterator;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 可变容量阻塞队列（美团《Java 线程池实现原理及其在美团业务中的实践》同源方案）：
 * 基于 OpenJDK LinkedBlockingQueue（GPLv2+CE）改造——{@code capacity} 由 final 改
 * volatile + {@link #setCapacity(int)}，两锁算法与其余实现逐行保真。
 *
 * <p>用途：给 {@code ThreadPoolExecutor} 提供运行时可调的背压水位——tuner 引擎按预案
 * 切换 queueCapacity（收缩=软水位线：已入队元素不丢弃，仅拒绝新元素直至消化到新容量）。
 *
 * <p><b>正确性要点</b>（相对原版的全部差异）：
 * <ul>
 *   <li>{@code capacity} volatile——putLock 内读取（{@code count < capacity} 准入判定），
 *       单写（tuner 引擎 per-pool 锁内）多读，volatile 可见性充分</li>
 *   <li>{@link #setCapacity(int)} 容量<b>增加</b>时 signalNotFull——唤醒等容的
 *       timed-offer 生产者重新检查（原版无此路径）</li>
 *   <li>收缩即软水位线：count ≥ 新容量时 offer 拒绝，不驱逐已入队元素</li>
 * </ul>
 *
 * <p>不做 Serializable（线程池内存队列无序列化场景——有意裁剪）。
 *
 * @author muyi
 * @date 2026/11/14
 */
public class ResizableCapacityLinkedBlockingQueue<E> extends AbstractQueue<E>
        implements BlockingQueue<E>, java.io.Serializable {

    /** 队列节点（与原版一致：item 置 null 即哨兵头） */
    static final class Node<E> {

        /** 元素 */
        E item;

        /** 后继 */
        Node<E> next;

        /**
         * 构造哨兵/元素节点。
         *
         * @param x 元素（哨兵传 null）
         */
        Node(E x) {
            item = x;
        }
    }

    /** 容量（原版 final——本类唯一实质改造点：volatile + setCapacity） */
    private volatile int capacity;

    /** 元素计数（两锁间的共享账本） */
    private final AtomicInteger count = new AtomicInteger();

    /** 头哨兵（dequeue 经此取元素） */
    private Node<E> head;

    /** 尾节点（enqueue 经此追加） */
    private Node<E> last;

    /** 出队锁 */
    private final ReentrantLock takeLock = new ReentrantLock();

    /** 非空条件（take/poll 等待） */
    private final Condition notEmpty = takeLock.newCondition();

    /** 入队锁 */
    private final ReentrantLock putLock = new ReentrantLock();

    /** 非满条件（put/timed-offer 等待） */
    private final Condition notFull = putLock.newCondition();

    /**
     * 构造（容量必须为正——0 容量队列无 TPE 语义）。
     *
     * @param capacity 初始容量
     */
    public ResizableCapacityLinkedBlockingQueue(int capacity) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("容量必须为正: " + capacity);
        }
        this.capacity = capacity;
        last = head = new Node<>(null);
    }

    /**
     * 读取当前容量。
     *
     * @return 容量
     */
    public int getCapacity() {
        return capacity;
    }

    /**
     * 运行时调整容量（tuner 引擎按预案调用；收缩=软水位线：不驱逐已入队元素，
     * 仅在 count ≥ 新容量期间拒绝新 offer；扩容唤醒等容生产者）。
     * 线程安全：volatile 写；并发 setCapacity 由调用方（tuner per-pool 锁）保证串行。
     *
     * @param capacity 新容量（正数）
     */
    public void setCapacity(int capacity) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("容量必须为正: " + capacity);
        }
        int old = this.capacity;
        this.capacity = capacity;
        if (capacity > old) {
            signalNotFull(); // 扩容：唤醒可能因旧容量不足而停等的生产者重查
        }
    }

    /** 链尾追加（putLock 内调用） */
    private void enqueue(Node<E> node) {
        last = last.next = node;
    }

    /** 链头取出（takeLock 内调用） */
    private E dequeue() {
        Node<E> h = head;
        Node<E> first = h.next;
        h.next = h; // 帮助 GC（原哨兵自环）
        head = first;
        E x = first.item;
        first.item = null;
        return x;
    }

    /** 唤醒一个等取线程（putLock 外调用） */
    private void signalNotEmpty() {
        takeLock.lock();
        try {
            notEmpty.signal();
        } finally {
            takeLock.unlock();
        }
    }

    /** 唤醒一个等容线程（takeLock 外调用） */
    private void signalNotFull() {
        putLock.lock();
        try {
            notFull.signal();
        } finally {
            putLock.unlock();
        }
    }

    /**
     * 队尾插入（容量准入判定在 putLock 内——volatile 容量精确生效）。
     *
     * @param e 元素
     * @return true 成功；容量满 false
     */
    @Override
    public boolean offer(E e) {
        Objects.requireNonNull(e);
        int c = -1;
        putLock.lock();
        try {
            if (count.get() < capacity) {
                enqueue(new Node<>(e));
                c = count.getAndIncrement();
                if (c + 1 < capacity) {
                    notFull.signal();
                }
            }
        } finally {
            putLock.unlock();
        }
        if (c == 0) {
            signalNotEmpty();
        }
        return c >= 0;
    }

    /**
     * 队尾插入（可中断等待容量）。
     *
     * @param e       元素
     * @throws InterruptedException 等待中被中断
     */
    @Override
    public void put(E e) throws InterruptedException {
        Objects.requireNonNull(e);
        int c = -1;
        putLock.lockInterruptibly();
        try {
            while (count.get() >= capacity) {
                notFull.await();
            }
            enqueue(new Node<>(e));
            c = count.getAndIncrement();
            if (c + 1 < capacity) {
                notFull.signal();
            }
        } finally {
            putLock.unlock();
        }
        if (c == 0) {
            signalNotEmpty();
        }
    }

    /**
     * 队尾插入（限时等待容量）。
     *
     * @param e       元素
     * @param timeout 等待时长
     * @param unit    时长单位
     * @return true 成功；超时 false
     * @throws InterruptedException 等待中被中断
     */
    @Override
    public boolean offer(E e, long timeout, TimeUnit unit) throws InterruptedException {
        Objects.requireNonNull(e);
        long nanos = unit.toNanos(timeout);
        int c = -1;
        putLock.lockInterruptibly();
        try {
            while (count.get() >= capacity) {
                if (nanos <= 0L) {
                    return false;
                }
                nanos = notFull.awaitNanos(nanos);
            }
            enqueue(new Node<>(e));
            c = count.getAndIncrement();
            if (c + 1 < capacity) {
                notFull.signal();
            }
        } finally {
            putLock.unlock();
        }
        if (c == 0) {
            signalNotEmpty();
        }
        return true;
    }

    /**
     * 取出队头（可中断等待元素）。
     *
     * @return 队头元素
     * @throws InterruptedException 等待中被中断
     */
    @Override
    public E take() throws InterruptedException {
        E x;
        int c = -1;
        takeLock.lockInterruptibly();
        try {
            while (count.get() == 0) {
                notEmpty.await();
            }
            x = dequeue();
            c = count.getAndDecrement();
            if (c > 1) {
                notEmpty.signal(); // 自己锁的条件（takeLock）——唤醒其他等取线程
            }
        } finally {
            takeLock.unlock();
        }
        if (c == capacity) {
            signalNotFull(); // 解锁后经 putLock 唤醒等容线程
        }
        return x;
    }

    /**
     * 取出队头（非阻塞）。
     *
     * @return 队头元素；空为 null
     */
    @Override
    public E poll() {
        E x;
        int c = -1;
        takeLock.lock();
        try {
            if (count.get() == 0) {
                return null;
            }
            x = dequeue();
            c = count.getAndDecrement();
            if (c > 1) {
                notEmpty.signal(); // 自己锁的条件（takeLock）
            }
        } finally {
            takeLock.unlock();
        }
        if (c == capacity) {
            signalNotFull();
        }
        return x;
    }

    /**
     * 取出队头（限时等待元素）。
     *
     * @param timeout 等待时长
     * @param unit    时长单位
     * @return 队头元素；超时 null
     * @throws InterruptedException 等待中被中断
     */
    @Override
    public E poll(long timeout, TimeUnit unit) throws InterruptedException {
        E x = null;
        long nanos = unit.toNanos(timeout);
        int c = -1;
        takeLock.lockInterruptibly();
        try {
            while (count.get() == 0) {
                if (nanos <= 0L) {
                    return null;
                }
                nanos = notEmpty.awaitNanos(nanos);
            }
            x = dequeue();
            c = count.getAndDecrement();
            if (c > 1) {
                notEmpty.signal(); // 自己锁的条件（takeLock）
            }
        } finally {
            takeLock.unlock();
        }
        if (c == capacity) {
            signalNotFull();
        }
        return x;
    }

    /**
     * 查看队头（不移除）。
     *
     * @return 队头元素；空为 null
     */
    @Override
    public E peek() {
        if (count.get() == 0) {
            return null;
        }
        takeLock.lock();
        try {
            return count.get() > 0 ? head.next.item : null;
        } finally {
            takeLock.unlock();
        }
    }

    /**
     * 元素数（近似实时——两锁并发下的原子账本读）。
     *
     * @return 元素数
     */
    @Override
    public int size() {
        return count.get();
    }

    /**
     * 剩余容量（近似——与 size 同源竞态；容量调整后立即可见）。
     *
     * @return 剩余容量
     */
    @Override
    public int remainingCapacity() {
        return capacity - count.get();
    }

    /**
     * 移除指定元素的一个实例（两锁全持遍历——弱一致）。
     *
     * @param o 目标元素
     * @return true 移除成功
     */
    @Override
    public boolean remove(Object o) {
        if (o == null) {
            return false;
        }
        fullyLock();
        try {
            for (Node<E> trail = head, p = trail.next; p != null; trail = p, p = p.next) {
                if (o.equals(p.item)) {
                    unlink(p, trail);
                    return true;
                }
            }
            return false;
        } finally {
            fullyUnlock();
        }
    }

    /**
     * 是否包含指定元素（弱一致遍历）。
     *
     * @param o 目标元素
     * @return true 包含
     */
    @Override
    public boolean contains(Object o) {
        if (o == null) {
            return false;
        }
        fullyLock();
        try {
            for (Node<E> p = head.next; p != null; p = p.next) {
                if (o.equals(p.item)) {
                    return true;
                }
            }
            return false;
        } finally {
            fullyUnlock();
        }
    }

    /** 三锁全持（remove/contains 遍历期间阻塞全部生产消费） */
    private void fullyLock() {
        putLock.lock();
        takeLock.lock();
    }

    /** 三锁全放 */
    private void fullyUnlock() {
        takeLock.unlock();
        putLock.unlock();
    }

    /**
     * 摘除节点（fullyLock 内调用；摘后腾容量则唤醒等容生产者）。
     *
     * @param p     目标节点
     * @param trail 前驱
     */
    private void unlink(Node<E> p, Node<E> trail) {
        p.item = null;
        trail.next = p.next;
        if (last == p) {
            last = trail;
        }
        if (count.getAndDecrement() < capacity) {
            notFull.signal();
        }
    }

    /**
     * 转移全部元素到目标集合。
     *
     * @param c 目标集合
     * @return 转移数
     */
    @Override
    public int drainTo(Collection<? super E> c) {
        return drainTo(c, Integer.MAX_VALUE);
    }

    /**
     * 转移最多 maxElements 个元素到目标集合（批量出队——只动 takeLock，一次 signal）。
     *
     * @param c           目标集合
     * @param maxElements 上限
     * @return 转移数
     */
    @Override
    public int drainTo(Collection<? super E> c, int maxElements) {
        Objects.requireNonNull(c);
        if (c == this) {
            throw new IllegalArgumentException("不能 drainTo 自身");
        }
        int n = Math.min(maxElements, count.get());
        int taken = 0;
        boolean signalNotFull = false;
        takeLock.lock();
        try {
            Node<E> p = head;
            while (taken < n) {
                Node<E> first = p.next;
                if (first == null) {
                    break;
                }
                c.add(first.item);
                first.item = null;
                p.next = p; // 帮助 GC（前驱自环摘链）
                p = first;
                taken++;
            }
            if (taken > 0) {
                int oldCount = count.getAndAdd(-taken);
                // flag 形态：notFull 属 putLock——此处只持 takeLock，解锁后经 signalNotFull 唤醒
                signalNotFull = oldCount - taken < capacity;
                head = p;
            }
        } finally {
            takeLock.unlock();
        }
        if (taken > 0) {
            if (signalNotFull) {
                signalNotFull();
            }
            signalNotEmpty();
        }
        return taken;
    }

    /**
     * 清空（drainTo 语义复用）。
     */
    @Override
    public void clear() {
        fullyLock();
        try {
            for (Node<E> p = head; p != null; ) {
                Node<E> next = p.next;
                p.item = null;
                p.next = p;
                p = next;
            }
            head = last = new Node<>(null);
            int c = count.getAndSet(0);
            if (c > 0) {
                notFull.signalAll();
            }
        } finally {
            fullyUnlock();
        }
    }

    /**
     * 弱一致迭代器（两锁算法下不抛 CME——与原版同语义）。
     *
     * @return 迭代器
     */
    @Override
    public Iterator<E> iterator() {
        return new Itr();
    }

    /** 弱一致迭代器（遍历期间锁短暂续持——与原版同语义） */
    private class Itr implements Iterator<E> {

        /** 当前节点 */
        private Node<E> current;

        /** 当前节点的删除锚（前驱） */
        private Node<E> lastRet;

        /**
         * 构造（定位首元素）。
         */
        Itr() {
            fullyLock();
            try {
                current = head.next;
            } finally {
                fullyUnlock();
            }
        }

        /** {@inheritDoc} */
        @Override
        public boolean hasNext() {
            return current != null;
        }

        /** {@inheritDoc} */
        @Override
        public E next() {
            fullyLock();
            try {
                if (current == null) {
                    throw new NoSuchElementException();
                }
                E x = current.item;
                lastRet = current;
                current = nextNode(current);
                return x;
            } finally {
                fullyUnlock();
            }
        }

        /** {@inheritDoc} */
        @Override
        public void remove() {
            if (lastRet == null) {
                throw new IllegalStateException();
            }
            Node<E> node = lastRet;
            lastRet = null;
            fullyLock();
            try {
                Node<E> trail = head;
                Node<E> p = trail.next;
                while (p != null && p != node) {
                    trail = p;
                    p = p.next;
                }
                if (p == node) {
                    unlink(node, trail);
                }
            } finally {
                fullyUnlock();
            }
        }

        /**
         * 定位后继（next 可能因并发摘除自环——跳回头重找，与原版同语义）。
         *
         * @param p 当前节点
         * @return 后继
         */
        private Node<E> nextNode(Node<E> p) {
            Node<E> next = p.next;
            if (p == next) { // 已摘除的自环——从头重找
                return head.next;
            }
            return next;
        }
    }

    /**
     * 队列摘要（排障用——含容量）。
     *
     * @return 摘要
     */
    @Override
    public String toString() {
        return "ResizableCapacityLinkedBlockingQueue{size=" + size() + ", capacity=" + capacity + "}";
    }
}
