package org.example.fraud.failure;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(FailureModeController.class)
@ActiveProfiles("dev")
class FailureModeControllerTest {

  @Autowired private MockMvc mockMvc;

  @MockitoBean private FailureModeService failureModeService;

  @Test
  void shouldSetFailureMode() throws Exception {
    when(failureModeService.setMode(FailureMode.ALWAYS_503)).thenReturn(FailureMode.ALWAYS_503);

    mockMvc
        .perform(
            post("/internal/test/failure-mode")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {"mode":"ALWAYS_503"}
                    """))
        .andExpect(status().isOk())
        .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
        .andExpect(jsonPath("$.mode").value("ALWAYS_503"));
  }
}
