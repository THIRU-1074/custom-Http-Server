package todo;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
class TodoApplicationTest {

    private static final String AUTH = "Bearer secret";

    @Autowired MockMvc mvc;

    @Test
    void crudAuthAndErrors() throws Exception {
        mvc.perform(get("/todos")).andExpect(status().isUnauthorized());
        mvc.perform(
                        post("/todos")
                                .header("Authorization", AUTH)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{}"))
                .andExpect(status().isBadRequest());
        mvc.perform(
                        post("/todos")
                                .header("Authorization", AUTH)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"title\":\"a\"}"))
                .andExpect(status().isCreated());
        mvc.perform(get("/todos/1").header("Authorization", AUTH)).andExpect(status().isOk());
        mvc.perform(get("/todos/9").header("Authorization", AUTH))
                .andExpect(status().isNotFound());
        mvc.perform(delete("/todos/1").header("Authorization", AUTH))
                .andExpect(status().isNoContent());
    }
}
