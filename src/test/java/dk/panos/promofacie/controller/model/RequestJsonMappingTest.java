package dk.panos.promofacie.controller.model;

import com.fasterxml.jackson.databind.ObjectMapper;
import dk.panos.promofacie.JacksonConfiguration;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** Uses the application's ObjectMapper customization (snake_case naming) to check the camelCase fields the SPA sends. */
class RequestJsonMappingTest {

    private static ObjectMapper appMapper() {
        ObjectMapper mapper = new ObjectMapper();
        new JacksonConfiguration().customize(mapper);
        return mapper;
    }

    @Test
    void verifyRequestReadsCamelCaseNonceIdFromTheFrontend() throws Exception {
        VerifyRequest req = appMapper().readValue("""
                {"address":"0xabc","stakeAddress":"0xabc","signature":"0xsig","chain":"ROBINHOOD",
                 "nonceId":"3f2b6c1e-8d4a-4f7e-9b1c-2a5d6e7f8a9b"}""", VerifyRequest.class);

        assertEquals("3f2b6c1e-8d4a-4f7e-9b1c-2a5d6e7f8a9b", req.nonceId());
        assertEquals("0xabc", req.getResolvedAddress());
        assertEquals("ROBINHOOD", req.chain());
    }

    @Test
    void verifyRequestWithoutNonceIdStillParses() throws Exception {
        VerifyRequest req = appMapper().readValue("""
                {"stakeAddress":"stake1u","signature":"84a4","key":"a401"}""", VerifyRequest.class);

        assertNull(req.nonceId());
        assertEquals("stake1u", req.getResolvedAddress());
    }

    @Test
    void challengeRequestReadsChainAndAddress() throws Exception {
        ChallengeRequest req = appMapper().readValue("""
                {"chain":"ROBINHOOD","address":"0xabc"}""", ChallengeRequest.class);

        assertEquals("ROBINHOOD", req.chain());
        assertEquals("0xabc", req.address());
    }
}
