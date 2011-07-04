package com.github.zhongl.jtoolkit;

import static com.github.zhongl.jtoolkit.CentralExecutor.Policy.*;
import static java.util.concurrent.Executors.*;

import java.util.List;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * {@link CentralExecutor} ֧�ֶԸ��� {@link Runnable} ��������߳���Դ������趨, ʵ���̳߳�ͳһ�滮����.
 *
 * @author <a href="mailto:zhong.lunfu@gmail.com">zhongl</>
 * @created 11-3-2
 */
public class CentralExecutor implements Executor {
  private static final Logger LOGGER = LoggerFactory.getLogger(CentralExecutor.class);
  private static final String CLASS_NAME = CentralExecutor.class.getSimpleName();

  private final ExecutorService service;
  private final Policy policy;
  private final Map<Class<? extends Runnable>, Submitter> quotas;
  private final int threadSize;

  private int reserved;

  public CentralExecutor(final int threadSize, Policy policy) {
    this.threadSize = threadSize;
    this.policy = policy;
    this.service = newFixedThreadPool(threadSize, new DebugableThreadFactory(CLASS_NAME));
    this.quotas = new ConcurrentHashMap<Class<? extends Runnable>, Submitter>();
  }

  public CentralExecutor(int threadSize) { this(threadSize, PESSIMISM); }

  /** @see ExecutorService#shutdownNow() */
  public List<Runnable> shutdownNow() { return service.shutdownNow(); }

  /** @see ExecutorService#shutdown() */
  public void shutdown() { service.shutdown(); }

  /** @see ExecutorService#isShutdown() */
  public boolean isShutdown() { return service.isShutdown(); }

  /** @see ExecutorService#isTerminated() */
  public boolean isTerminated() { return service.isTerminated(); }

  /** @see ExecutorService#awaitTermination(long, java.util.concurrent.TimeUnit) */
  public boolean awaitTermination(long timeout, TimeUnit unit) throws InterruptedException {
    return service.awaitTermination(timeout, unit);
  }

  @Override
  public void execute(Runnable task) {
    final Submitter submitter = quotas.get(task.getClass());
    if (submitter != null) submitter.submit(task, this);
    else policy.defaultSubmitter().submit(task, this);
  }

  /** @return Ԥ�����. */
  public static Quota reserve(int value) { return new Quota(value); }

  /** @return �������. */
  public static Quota elastic(int value) { return new Quota(value); }

  /** @return �����. */
  public static Quota nil() { return new Quota(0); }

  /**
   * �趨taskClass�ı������������.
   *
   * @param taskClass
   * @param reserve
   * @param elastic
   *
   * @throws IllegalArgumentException
   */
  public void quota(Class<? extends Runnable> taskClass, Quota reserve, Quota elastic) {

    synchronized (this) {
      if (reserve.value > threadSize - reserved) throw new IllegalArgumentException("No resource for reserve");
      reserved += reserve.value;
    }

    quotas.put(taskClass, policy.submitter(reserve, elastic));
  }

  private synchronized boolean hasUnreserved() { return threadSize > reserved; }

  /** {@link Quota} */
  private final static class Quota {
    private final AtomicInteger state;
    private final int value;

    private Quota(int value) {
      if (value < 0) throw new IllegalArgumentException("Quota should not less than 0.");
      this.value = value;
      this.state = new AtomicInteger(value);
    }

    /** @return ��ǰʣ�����. */
    public int state() { return state.get(); }

    /**
     * ռ��һ�����.
     *
     * @return false ��ʾԤ�������������, ��֮Ϊtrue.
     */
    public boolean acquire() {
      if (state() == 0) return false;
      if (state.decrementAndGet() >= 0) return true;
      state.incrementAndGet();
      return false;
    }

    /**
     * �ͷ�һ�����.
     *
     * @return false ��ʾ��Ч���ͷ�, ��������²�Ӧ����, ��֮Ϊtrue.
     */
    public boolean release() {
      if (state() == value) return false;
      if (state.incrementAndGet() <= value) return true;
      state.decrementAndGet();
      return false;
    }

  }

  /** {@link Policy} */
  public static enum Policy {

    /** �ֹ۲���, �ڴ���Ϊ�������������, һ�����������߳�, ����������ռ, ��ռ�����ȼ����ύ���Ⱥ�˳�����. */
    OPTIMISM {

      /** δ������������ֱ�ӽ���ȴ�����, �����ȼ��������ж�������������. */
      private final Submitter defaultSubmitter = new Submitter() {
        @Override
        public void submit(Runnable task, CentralExecutor executor) { enqueue(new ComparableTask(task, Integer.MAX_VALUE)); }
      };

      @Override
      Submitter defaultSubmitter() { return defaultSubmitter; }

      @Override
      Submitter submitter(final Quota reserve, final Quota elastic) {
        return new Submitter() {
          @Override
          public void submit(final Runnable task, CentralExecutor executor) {
            if (reserve.acquire()) doSubmit(task, executor, reserve);
              // ������Ϊ�����Ԥ�����, ��������������
            else if (executor.hasUnreserved() && elastic.acquire()) doSubmit(task, executor, elastic);
              // ͬ���۲��Խ���ȴ�����
            else enqueue(new ComparableTask(task, reserve.value));
          }
        };
      }
    },

    /** ���۲���, �������̶߳���Ԥ���������, ��ʹ��ǰԤ��֮����߳��ǿ���, Ҳ���ᱻ��ռ, ��Elastic���趨��������. */
    PESSIMISM {

      private final Submitter defaultSubmitter = new Submitter() {
        @Override
        public void submit(Runnable task, CentralExecutor executor) {
          throw new RejectedExecutionException("Unquotaed task can not be executed in pessimism.");
        }
      };

      @Override
      Submitter defaultSubmitter() { return defaultSubmitter; }

      @Override
      Submitter submitter(final Quota reserve, final Quota elastic) {
        if (reserve.value == 0)
          throw new IllegalArgumentException("None-reserve task will never be executed in pessimism.");

        return new Submitter() {
          @Override
          public void submit(final Runnable task, CentralExecutor executor) {
            if (reserve.acquire()) doSubmit(task, executor, reserve);
              // �ľ�Ԥ������, ����ȴ�����, ��Ԥ����ȴ�С�����ȼ�, ��������.
            else enqueue(new ComparableTask(task, reserve.value));
          }
        };
      }
    };


    /** ���ȼ��ȴ�����. */
    private final PriorityBlockingQueue<ComparableTask> queue = new PriorityBlockingQueue<ComparableTask>();

    abstract Submitter submitter(Quota reserve, Quota elastic);

    abstract Submitter defaultSubmitter();

    /** ��������ȴ�����. */
    void enqueue(ComparableTask task) {
      queue.put(task);
      LOGGER.debug("Enqueue {}", task.original);
    }

    /** ��������������ύ��ִ����. */
    void dequeueTo(CentralExecutor executor) {
      try {
        final Runnable task = queue.take().original;
        LOGGER.debug("Dequeue {}", task);
        executor.execute(task);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        LOGGER.debug("Dequeue has been interrupted ", e);
      }
    }

    void doSubmit(Runnable task, CentralExecutor executor, Quota quota) {
      executor.service.execute(new Decorator(task, quota, executor));
    }

    /** {@link ComparableTask} */
    static class ComparableTask implements Comparable<ComparableTask> {
      final Runnable original;
      private final int quota;

      public ComparableTask(Runnable task, int quota) {
        this.original = task;
        this.quota = quota;
      }

      @Override
      public int compareTo(ComparableTask o) { return -(quota - o.quota); }
    }

    /** {@link Decorator} */
    class Decorator implements Runnable {
      private final Runnable task;
      private final Quota quota;
      private final CentralExecutor executor;

      public Decorator(Runnable task, Quota quota, CentralExecutor executor) {
        this.task = task;
        this.quota = quota;
        this.executor = executor;
      }

      @Override
      public void run() {
        try {
          task.run();
        } catch (Throwable t) {
          LOGGER.error("Unexpected Interruption cause by", t);
        } finally {
          quota.release();
          dequeueTo(executor);
        }
      }
    }
  }

  /** {@link Submitter} */
  private static interface Submitter {
    void submit(Runnable task, CentralExecutor executor);
  }
}
