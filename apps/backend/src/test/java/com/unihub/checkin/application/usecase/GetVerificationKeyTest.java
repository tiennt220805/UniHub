package com.unihub.checkin.application.usecase;

import static org.assertj.core.api.Assertions.assertThat;

import com.unihub.checkin.application.port.out.VerificationKeys;
import com.unihub.checkin.application.result.VerificationKeyResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class GetVerificationKeyTest {

    @Test
    @DisplayName("the key is whatever the port says is current, read on every call")
    void returnsTheCurrentKey_onEveryCall() {
        var keys = new Rotating();
        var useCase = new GetVerificationKey(keys);

        var first = useCase.current();
        keys.rotate();
        var second = useCase.current();

        assertThat(first).isEqualTo(new VerificationKeyResult("EC", "P-256", "x1", "y1"));
        assertThat(second)
                .as("a rotation shows at the next fetch, nothing is cached here")
                .isEqualTo(new VerificationKeyResult("EC", "P-256", "x2", "y2"));
    }

    private static final class Rotating implements VerificationKeys {
        private int generation = 1;

        void rotate() {
            generation++;
        }

        @Override
        public VerificationKeyResult current() {
            return new VerificationKeyResult("EC", "P-256", "x" + generation, "y" + generation);
        }
    }
}
