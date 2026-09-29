package dev.productivity.signals.controller;

import dev.productivity.signals.service.AiCorrectionService;
import dev.productivity.signals.service.AiEvaluationFeedbackService;
import dev.productivity.signals.service.AiEvaluationService;
import dev.productivity.signals.service.AiMrAnalysisBatchService;
import dev.productivity.signals.service.DemoMockDataService;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class AiControllerManualRunTest {

    @Test
    void publicDemoReportsDisabledAndRejectsBothExecutionRoutes() throws Exception {
        AiCorrectionService correctionService = mock(AiCorrectionService.class);
        AiMrAnalysisBatchService batchService = mock(AiMrAnalysisBatchService.class);
        AiController controller = new AiController(
                mock(AiEvaluationService.class),
                correctionService,
                batchService,
                mock(DemoMockDataService.class),
                mock(AiEvaluationFeedbackService.class));
        ReflectionTestUtils.setField(controller, "manualRunEnabled", false);
        var mvc = MockMvcBuilders.standaloneSetup(controller).build();

        mvc.perform(get("/api/ai/mr-analysis-settings"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.manualRunEnabled").value(false));
        mvc.perform(post("/api/ai/mr-analysis-jobs")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/ai/mr-analysis-batch")
                        .param("projectIds", "github~owner~repo")
                        .param("since", "2026-09-01")
                        .param("until", "2026-09-29"))
                .andExpect(status().isForbidden());

        verifyNoInteractions(correctionService, batchService);
    }
}
