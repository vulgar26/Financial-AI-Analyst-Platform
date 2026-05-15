package com.travel.ai.task;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app.agent-task")
public class AgentTaskProperties {

    private Worker worker = new Worker();
    private int defaultMaxRetries = 0;

    public Worker getWorker() {
        if (worker == null) {
            worker = new Worker();
        }
        return worker;
    }

    public void setWorker(Worker worker) {
        this.worker = worker != null ? worker : new Worker();
    }

    public int getDefaultMaxRetries() {
        return Math.max(0, defaultMaxRetries);
    }

    public void setDefaultMaxRetries(int defaultMaxRetries) {
        this.defaultMaxRetries = defaultMaxRetries;
    }

    public static class Worker {
        private boolean enabled = true;
        private int batchSize = 5;
        private long leaseSeconds = 60;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public int getBatchSize() {
            return Math.max(1, batchSize);
        }

        public void setBatchSize(int batchSize) {
            this.batchSize = batchSize;
        }

        public long getLeaseSeconds() {
            return Math.max(1L, leaseSeconds);
        }

        public void setLeaseSeconds(long leaseSeconds) {
            this.leaseSeconds = leaseSeconds;
        }
    }
}

