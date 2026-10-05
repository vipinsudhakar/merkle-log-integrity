package com.merklelog.persistence;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Seeds the demo datasets on startup when the database is empty.
 *
 * <p>Render's free Postgres is wiped every 30 days; with this, a fresh database becomes usable
 * on the next start without anyone calling {@code POST /api/admin/seed}. Disable with
 * {@code app.seed-on-startup=false}.
 */
@Component
@ConditionalOnProperty(name = "app.seed-on-startup", havingValue = "true", matchIfMissing = true)
public class DemoDataSeeder implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(DemoDataSeeder.class);

    private final DatasetService datasets;

    public DemoDataSeeder(DatasetService datasets) {
        this.datasets = datasets;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (datasets.isEmpty()) {
            log.info("Empty database: seeding demo datasets {}", DatasetService.DEMO_DATASETS);
            datasets.seedDemoData();
        }
    }
}
