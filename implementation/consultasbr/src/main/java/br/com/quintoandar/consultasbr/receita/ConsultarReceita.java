package br.com.quintoandar.consultasbr.receita;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.URL;
import java.util.concurrent.TimeUnit;

import org.apache.http.HttpResponse;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.methods.HttpPost;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import br.com.quintoandar.consultasbr.core.SimpleHttpQuerier;
import br.com.quintoandar.consultasbr.pf.RespostaCaptcha;

public class ConsultarReceita extends SimpleHttpQuerier {
	private static final String ENCODING = "ISO-8859-1";

	private static final Logger log = LoggerFactory.getLogger(ConsultarReceita.class);

	public RespostaCaptcha requestCaptcha() {
		HttpGet httpGet = new HttpGet("http://www.receita.fazenda.gov.br/aplicacoes/atcta/cpf/ConsultaPublica.asp");
		httpGet.setHeader("User-Agent", "Mozilla/5.0 (X11; Ubuntu; Linux x86_64; rv:29.0) Gecko/20100101 Firefox/29.0");

		try {
			HttpResponse resp = client.execute(httpGet);
			if (resp.getStatusLine().getStatusCode() == 200) {
				ByteArrayOutputStream baos = new ByteArrayOutputStream();
				resp.getEntity().writeTo(baos);
				String contentAsString = new String(baos.toByteArray(), ENCODING).replaceAll("[\n\r]", "");

				Document doc = Jsoup.parse(contentAsString.replaceAll("&nbsp;", " "));
				contentAsString = null;

				Element viewstate = doc.getElementById("viewstate");
				RespostaCaptcha captchaAnswer = new RespostaCaptcha(viewstate.val());

				Element capimg = doc.getElementById("imgcaptcha");
				String urlOriginalCaptcha = "http://www.receita.fazenda.gov.br"+capimg.attr("src");
				System.out.println(urlOriginalCaptcha);

				doc = null;

				URL urlCaptcha = new URL(urlOriginalCaptcha);
				InputStream captchaStream = urlCaptcha.openStream();
				ByteArrayOutputStream captchaBytes = new ByteArrayOutputStream();
				copy(captchaStream, captchaBytes, 3);
				captchaBytes.close();
				captchaAnswer.setCaptchaImage(captchaBytes.toByteArray());

				return captchaAnswer;
			}
		} catch (Throwable e) {
			log.error("Erro", e);
		} finally {
			httpGet.abort();
			connMan.closeIdleConnections(1, TimeUnit.MILLISECONDS);

		}
		return null;
	}


	public ResultadoConsutaCPF consultarCPF(RespostaCaptcha captcha, String respCaptcha, String cpf) {
		if (captcha.getSessionId() == null) {
			throw new IllegalArgumentException("viewstate must be specified");
		}
		return consultarAntecedentes(captcha.getSessionId(), respCaptcha, cpf != null ? cpf.replaceAll("[^0-9]", "") : cpf);
	}

	private ResultadoConsutaCPF consultarAntecedentes(String sessionId, String respCaptcha, String cpf) {
		HttpPost httpPost = new HttpPost("http://www.receita.fazenda.gov.br/aplicacoes/atcta/cpf/ConsultaPublicaExibir.asp");

		ResultadoConsutaCPF res = new ResultadoConsutaCPF();
		httpPost.setEntity(asKeyValueEntity(ENCODING, "viewstate", sessionId, "txtCPF", cpf, "captcha",
				respCaptcha, "captchaAudio", "", "Enviar", "Consultar"));
		try {
			HttpResponse resp = client.execute(httpPost);
			if (resp.getStatusLine().getStatusCode() == 200) {
				ByteArrayOutputStream baos = new ByteArrayOutputStream();
				resp.getEntity().writeTo(baos);
				baos.close();
				String contentAsString = new String(baos.toByteArray(), ENCODING).replaceAll("[\n\r]", "");
				
				res.setPdf(getHtmlWithBase(contentAsString,ENCODING,"http://www.receita.fazenda.gov.br"));
				
				Document doc = Jsoup.parse(contentAsString.replaceAll("&nbsp;", " "));
				baos = null;
				contentAsString = null;

				for (Element el : doc.select(".clConteudoDados")) {
					if (el.text().contains("Nome da Pessoa")) {
						res.setNome(el.text().split(":")[1].trim());
					} else if (el.text().contains("Situação Cadastral")) {
						res.setStatus(StatusCPF.IRREGULAR);
						try {
							res.setStatus(StatusCPF.valueOf(el.text().split(":")[1].trim()));
						} catch (Throwable t) {
							log.error("Error determining CPF status: fallback to IRREGULAR", t);
						}
					}
				}

				for (Element el : doc.select(".clConteudoComp")) {
					if (el.text().contains("Código de controle do comprovante")) {
						res.setCodComprovante(el.text().split(":")[1].trim());
					}
				}
			}
		} catch (Throwable e) {
			log.error("Erro", e);
		} finally {
			httpPost.abort();
			connMan.closeIdleConnections(1, TimeUnit.MILLISECONDS);

		}
		return res;
	}
}

