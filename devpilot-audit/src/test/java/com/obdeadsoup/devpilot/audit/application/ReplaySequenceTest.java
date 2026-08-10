package com.obdeadsoup.devpilot.audit.application;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class ReplaySequenceTest {
    private final ReplaySequence sequence=new ReplaySequence();
    @Test void advancesFromStoredMaximum(){assertThat(sequence.next(0)).isEqualTo(1);assertThat(sequence.next(7)).isEqualTo(8);}
    @Test void rejectsInvalidBounds(){assertThatThrownBy(()->sequence.next(-1)).isInstanceOf(IllegalArgumentException.class);}
}
