package ws.mia.poseidon.core.controller;

import jakarta.servlet.RequestDispatcher;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.boot.web.servlet.error.ErrorController;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseBody;

@Controller
public class PoseidonErrorController implements ErrorController {

	@RequestMapping(value = "/error", produces = MediaType.TEXT_PLAIN_VALUE)
	@ResponseBody
	public String handleError(HttpServletRequest request) {
		return request.getAttribute(RequestDispatcher.ERROR_STATUS_CODE) + "\n" +  request.getAttribute(RequestDispatcher.ERROR_MESSAGE);
	}
}