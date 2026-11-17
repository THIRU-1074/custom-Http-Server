package in.jolt;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class HttpStatusTest {

    @Test
    void commonCodesHaveTheirOwnPhrase() {
        assertEquals("See Other", HttpStatus.reasonFor(303));
        assertEquals("Temporary Redirect", HttpStatus.reasonFor(307));
        assertEquals("Permanent Redirect", HttpStatus.reasonFor(308));
        assertEquals("Gone", HttpStatus.reasonFor(410));
        assertEquals("I'm a Teapot", HttpStatus.reasonFor(418));
        assertEquals("Misdirected Request", HttpStatus.reasonFor(421));
        assertEquals("Precondition Required", HttpStatus.reasonFor(428));
        assertEquals("Unavailable For Legal Reasons", HttpStatus.reasonFor(451));
        assertEquals("Gateway Timeout", HttpStatus.reasonFor(504));
        assertEquals("Network Authentication Required", HttpStatus.reasonFor(511));
        assertEquals("Not Found", HttpStatus.reasonFor(404));
    }

    @Test
    void unlistedCodesFallBackToTheirClass() {
        assertEquals("Informational", HttpStatus.reasonFor(199));
        assertEquals("Success", HttpStatus.reasonFor(299));
        assertEquals("Redirection", HttpStatus.reasonFor(399));
        assertEquals("Client Error", HttpStatus.reasonFor(499));
        assertEquals("Server Error", HttpStatus.reasonFor(599));
        assertEquals("Unknown", HttpStatus.reasonFor(99));
        assertEquals("Unknown", HttpStatus.reasonFor(600));
    }
}
