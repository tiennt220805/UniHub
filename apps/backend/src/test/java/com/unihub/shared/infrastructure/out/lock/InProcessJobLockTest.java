package com.unihub.shared.infrastructure.out.lock;

import com.unihub.shared.application.port.out.JobLock;

class InProcessJobLockTest extends JobLockContractTest {

    @Override
    protected JobLock newLock() {
        return new InProcessJobLock();
    }
}
