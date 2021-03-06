/*
 * Copyright (c) 2011-2016 Pivotal Software Inc, All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package reactor.core.scheduler;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

import reactor.core.flow.Cancellation;
import reactor.core.scheduler.Scheduler;
import reactor.core.state.Cancellable;
import reactor.core.util.Exceptions;

/**
 * A simple {@link Scheduler} which uses a backing {@link ExecutorService} to schedule Runnables for async operators.
 */
final class ExecutorServiceScheduler implements Scheduler {

	static final Runnable EMPTY = () -> {

	};

	static final Future<?> CANCELLED_FUTURE = new FutureTask<>(EMPTY, null);

	static final Future<?> FINISHED = new FutureTask<>(EMPTY, null);

	final ExecutorService executor;

	public ExecutorServiceScheduler(ExecutorService executor) {
		this.executor = executor;
	}
	
	@Override
	public Worker createWorker() {
		return new ExecutorServiceWorker(executor);
	}
	
	@Override
	public Cancellation schedule(Runnable task) {
		Future<?> f = executor.submit(task);
		return () -> f.cancel(true);
	}

	static final class ExecutorServiceWorker implements Worker {
		
		final ExecutorService executor;
		
		volatile boolean terminated;
		
		Collection<ScheduledRunnable> tasks;
		
		public ExecutorServiceWorker(ExecutorService executor) {
			this.executor = executor;
			this.tasks = new LinkedList<>();
		}
		
		@Override
		public Cancellation schedule(Runnable t) {
			ScheduledRunnable sr = new ScheduledRunnable(t, this);
			if (add(sr)) {
				Future<?> f = executor.submit(sr);
				sr.setFuture(f);
			}
			return sr;
		}
		
		boolean add(ScheduledRunnable sr) {
			if (!terminated) {
				synchronized (this) {
					if (!terminated) {
						tasks.add(sr);
						return true;
					}
				}
			}
			return false;
		}
		
		void delete(ScheduledRunnable sr) {
			if (!terminated) {
				synchronized (this) {
					if (!terminated) {
						tasks.remove(sr);
					}
				}
			}
		}
		
		@Override
		public void shutdown() {
			if (!terminated) {
				Collection<ScheduledRunnable> coll;
				synchronized (this) {
					if (terminated) {
						return;
					}
					coll = tasks;
					tasks = null;
					terminated = true;
				}
				for (ScheduledRunnable sr : coll) {
					sr.cancelFuture();
				}
			}
		}
	}
	
	static final class ScheduledRunnable
	extends AtomicReference<Future<?>>
	implements Runnable, Cancellable, Cancellation {
		/** */
		private static final long serialVersionUID = 2284024836904862408L;
		
		final Runnable task;
		
		final ExecutorServiceWorker parent;
		
		volatile Thread current;
		static final AtomicReferenceFieldUpdater<ScheduledRunnable, Thread> CURRENT =
				AtomicReferenceFieldUpdater.newUpdater(ScheduledRunnable.class, Thread.class, "current");

		public ScheduledRunnable(Runnable task, ExecutorServiceWorker parent) {
			this.task = task;
			this.parent = parent;
		}
		
		@Override
		public void run() {
			CURRENT.lazySet(this, Thread.currentThread());
			try {
				try {
					task.run();
				} catch (Throwable e) {
					Exceptions.onErrorDropped(e);
				}
			} finally {
				for (;;) {
					Future<?> a = get();
					if (a == CANCELLED_FUTURE) {
						break;
					}
					if (compareAndSet(a, FINISHED)) {
						parent.delete(this);
						break;
					}
				}
				CURRENT.lazySet(this, null);
			}
		}
		
		void doCancel(Future<?> a) {
			a.cancel(Thread.currentThread() != current);
		}
		
		void cancelFuture() {
			for (;;) {
				Future<?> a = get();
				if (a == FINISHED) {
					return;
				}
				if (compareAndSet(a, CANCELLED_FUTURE)) {
					if (a != null) {
						doCancel(a);
					}
					return;
				}
			}
		}
		
		@Override
		public boolean isCancelled() {
			Future<?> f = get();
			return f == FINISHED || f == CANCELLED_FUTURE;
		}
		
		@Override
		public void dispose() {
			for (;;) {
				Future<?> a = get();
				if (a == FINISHED) {
					return;
				}
				if (compareAndSet(a, CANCELLED_FUTURE)) {
					if (a != null) {
						doCancel(a);
					}
					parent.delete(this);
					return;
				}
			}
		}

		
		void setFuture(Future<?> f) {
			for (;;) {
				Future<?> a = get();
				if (a == FINISHED) {
					return;
				}
				if (a == CANCELLED_FUTURE) {
					doCancel(a);
					return;
				}
				if (compareAndSet(null, f)) {
					return;
				}
			}
		}
		
		@Override
		public String toString() {
			return "ScheduledRunnable[cancelled=" + get() + ", task=" + task + "]";
		}
	}
}
