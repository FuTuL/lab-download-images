import java.io.File;
import java.io.IOException;
import java.net.URL;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.logging.Logger;

import static java.util.concurrent.TimeUnit.MILLISECONDS;

public class Controller {
    public static final String SUCCESS = "success";
    public static final String SKIPPED = "skipped";
    public static final String FAILED = "failed";

    private final String startLink;
    private final int sizeLimit;
    private final String saveDir;
    private final int recursionLimit;

    private final ThreadPoolExecutor imageTP;
    private final ThreadPoolExecutor documentTP;
    private final ReadWriteLock tpLock = new ReentrantReadWriteLock();

    private final ScheduledExecutorService logService = Executors.newScheduledThreadPool(1);
    private final Logger logger;
    private final int logPeriod;
    private final Map<String, AtomicInteger> summary = new HashMap<>(3);

    private final HashSet<URL> checked = new HashSet<>();
    private final Lock checkedLock = new ReentrantLock();

    private final ScheduledExecutorService waiter = Executors.newScheduledThreadPool(1);

    public Controller(Properties props) {
        var imageThreads = Integer.parseInt(props.getProperty("image.thread_pool", "100"));
        if (imageThreads < 1) {
            imageThreads = 1;
        }
        var documentThreads = Integer.parseInt(props.getProperty("document.thread_pool", "100"));
        if (documentThreads < 1) {
            documentThreads = 1;
        }
        imageTP = (ThreadPoolExecutor) Executors.newFixedThreadPool(imageThreads);
        documentTP = (ThreadPoolExecutor) Executors.newFixedThreadPool(documentThreads);
        var tmp = Integer.parseInt(props.getProperty("log.period_ms", "500"));
        if (tmp < 1) {
            tmp = 1;
        }
        logPeriod = tmp;
        tmp = Integer.parseInt(props.getProperty("document.recursion_limit", "0"));
        if (tmp < 0) {
            tmp = 0;
        }
        recursionLimit = tmp;
        tmp = Integer.parseInt(props.getProperty("image.size_limit_bytes", "20480"));
        if (tmp < 0) {
            tmp = 0;
        }
        sizeLimit = tmp;
        saveDir = props.getProperty("image.save_dir", "data/images");
        startLink = props.getProperty("document.start_link", "http://example.com/");
        logger = Logger.getLogger(Logger.GLOBAL_LOGGER_NAME);
        summary.put(SUCCESS, new AtomicInteger(0));
        summary.put(SKIPPED, new AtomicInteger(0));
        summary.put(FAILED, new AtomicInteger(0));
    }

    // run recursive document loading in ThreadPool + logging + waiting complete
    public void Run() throws IOException {
        // create dir if need
        //noinspection ResultOfMethodCallIgnored
        new File(saveDir).mkdirs();

        var url = new URL(startLink);
        var document = new LoadDocument(
                imageTP,
                documentTP,
                tpLock,
                checked,
                checkedLock,
                summary
        );

        document.url = url;
        document.recursionLimit = recursionLimit;
        document.saveDir = saveDir;
        document.sizeLimit = sizeLimit;

        documentTP.submit(document);

        _log();
        _shutdownWhenDone();
    }

    // just logging state
    private void _log() {
        logService.scheduleAtFixedRate(() -> {
            tpLock.readLock().lock();
            var queue = imageTP.getQueue().size();
            var active = imageTP.getActiveCount();
            logger.info(String.format(
                    "Queue size: %6d\tActive: %6d\tSuccess: %6d\tSkipped: %6d\tFailed: %6d\n",
                    queue,
                    active,
                    summary.get("success").get(),
                    summary.get("skipped").get(),
                    summary.get("failed").get()
            ));
            tpLock.readLock().unlock();
        }, logPeriod, logPeriod, MILLISECONDS);
    }

    // wait when all ThreadPools will be empty and then shutdown it and log total work time
    private void _shutdownWhenDone() {
        var start = System.currentTimeMillis();
        waiter.scheduleAtFixedRate(() -> {
            tpLock.readLock().lock();
            var queue = imageTP.getActiveCount() + imageTP.getQueue().size() +
                    documentTP.getActiveCount() + documentTP.getQueue().size();
            tpLock.readLock().unlock();
            if (queue == 0) {
                var diff = System.currentTimeMillis() - start;
                imageTP.shutdown();
                documentTP.shutdown();
                logService.shutdown();
                waiter.shutdown();
                logger.info(String.format("Total images loaded: %d for %.2f seconds\n", summary.get("success").get(), diff / 1000f));
            }
        }, 100, 100, MILLISECONDS);
    }
}
