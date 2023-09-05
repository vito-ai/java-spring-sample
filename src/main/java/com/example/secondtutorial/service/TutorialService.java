package com.example.secondtutorial.service;


import lombok.extern.slf4j.Slf4j;

import okhttp3.*;

import okio.ByteString;
import org.json.JSONException;
import org.json.JSONObject;

import org.springframework.core.io.FileSystemResource;

import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.MultipartBodyBuilder;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.reactive.function.BodyInserters;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import java.io.*;


import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.concurrent.CountDownLatch;
import java.util.logging.Level;
import java.util.logging.Logger;



@Service
@Slf4j
public class TutorialService {

    private boolean stopPolling = false;
    private String transcribeId = null;
    private String accessToken = null;


    public String getAccessToken(){
        WebClient webClient = WebClient.builder()
                .baseUrl("https://openapi.vito.ai")
                .build();

        MultiValueMap<String, String> formData = new LinkedMultiValueMap<>();
        formData.add("client_id","{YOUR_CLIENT_ID}");
        formData.add("client_secret", "{YOUR_CLIENT_SECRET}");


        String response = webClient
                .post()
                .uri("/v1/authenticate")
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .body(BodyInserters.fromFormData(formData))
                .retrieve()
                .bodyToMono(String.class)
                .block();

        log.info(response);
        JSONObject jsonObject = new JSONObject(response.toString());
        return jsonObject.getString("access_token");
    }


    public void transcribeFile(MultipartFile multipartFile) throws IOException, InterruptedException {

        accessToken = getAccessToken();
        WebClient webClient = WebClient.builder()
                .baseUrl("https://openapi.vito.ai/v1")
                .defaultHeader(HttpHeaders.CONTENT_TYPE, String.valueOf(MediaType.MULTIPART_FORM_DATA))
                .defaultHeader(HttpHeaders.AUTHORIZATION, "bearer " + accessToken)
                .build();

        Path currentPath = Paths.get("");
        File file = new File(currentPath.toAbsolutePath().toString() + "/"+multipartFile.getOriginalFilename());
        multipartFile.transferTo(file);

        MultipartBodyBuilder multipartBodyBuilder = new MultipartBodyBuilder();
        multipartBodyBuilder.part("file", new FileSystemResource(file));
        multipartBodyBuilder.part("config", "{}");


        // POST 요청 보내기
        String response = null;
        try{
            response = webClient.post()
                    .uri("/transcribe")
                    .body(BodyInserters.fromMultipartData(multipartBodyBuilder.build()))
                    .retrieve()
                    .bodyToMono(String.class)
                    .block();
            log.info("post 끝");
        }catch (WebClientResponseException e){
            log.error(String.valueOf(e));
        }

        JSONObject jsonObject = new JSONObject(response.toString());

        try{
            if(jsonObject.getString("code").equals("H0002")){
                log.info("accessToken 만료로 재발급 받습니다");
                accessToken = getAccessToken();
                response = webClient.post()
                        .uri("/transcribe")
                        .body(BodyInserters.fromMultipartData(multipartBodyBuilder.build()))
                        .retrieve()
                        .bodyToMono(String.class)
                        .block();
            }
        }catch (JSONException e){
            log.info("code 확인 불가 오류 catch");
            log.info(e.toString());
        }
        log.info("transcribe 요청 id : " + jsonObject.getString("id"));

        stopPolling = false;
        Thread.sleep(10);
        transcribeId = jsonObject.getString("id");
        startPolling();
    }


    @Async
    @Scheduled(fixedRate = 5000) // 5초마다 실행 (주기는 필요에 따라 조절)
    public void startPolling() {
        log.info("Polling 함수 첫 시작");
        while (!stopPolling) {
            log.info("while polling 시작 반복중");
            WebClient webClient = WebClient.builder()
                    .baseUrl("https://openapi.vito.ai/v1")
                    .defaultHeader(HttpHeaders.AUTHORIZATION, "bearer " + accessToken)
                    .build();


            String uri = "/transcribe/" + transcribeId;
            String response = webClient.get()
                    .uri(uri)
                    .retrieve()
                    .bodyToMono(String.class)
                    .block();
            log.info("get 요청 날림");



            JSONObject jsonObject = new JSONObject(response.toString());
            // status 확인하여 폴링 중단 여부 결정
            if (jsonObject.getString("status").equals("completed")) {
                stopPolling = true;
            }

            try {
                Thread.sleep(5000); // 폴링 주기 (5초)를 설정
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            log.info("while polling 끝 반복중");
        }
        log.info("폴링함수 끝");
    }






    public void transcribeWebSocketFile(MultipartFile multipartFile) throws IOException, InterruptedException {
        Logger logger = Logger.getLogger(TutorialService.class.getName());
        OkHttpClient client = new OkHttpClient();
        String token = getAccessToken();

        HttpUrl.Builder httpBuilder = HttpUrl.get("https://openapi.vito.ai/v1/transcribe:streaming").newBuilder();
        httpBuilder.addQueryParameter("sample_rate", "44100");
        httpBuilder.addQueryParameter("encoding", "WAV");
        httpBuilder.addQueryParameter("use_itn", "true");
        httpBuilder.addQueryParameter("use_disfluency_filter", "true");
        httpBuilder.addQueryParameter("use_profanity_filter", "true");

        String url = httpBuilder.toString().replace("https://", "wss://");

        Request request = new Request.Builder()
                .url(url)
                .addHeader("Authorization", "Bearer " + token)
                .build();

        VitoWebSocketListener webSocketListener = new VitoWebSocketListener();
        WebSocket vitoWebSocket = client.newWebSocket(request, webSocketListener);

        FileInputStream fis = null;
        Path currentPath = Paths.get("");
        File file = new File(currentPath.toAbsolutePath().toString() + "/"+multipartFile.getOriginalFilename());
        multipartFile.transferTo(file);
        try {
            fis = new FileInputStream(file);
        } catch (FileNotFoundException e) {
            e.printStackTrace();
            System.exit(1);
        }

        byte[] buffer = new byte[1024];
        int readBytes;
        while ((readBytes = fis.read(buffer)) != -1) {
            boolean sent = vitoWebSocket.send(ByteString.of(buffer, 0, readBytes));
            if (!sent) {
                logger.log(Level.WARNING, "Send buffer is full. Cannot complete request. Increase sleep interval.");
                System.exit(1);
            }
            Thread.sleep(0, 100);
        }
        fis.close();
        vitoWebSocket.send("EOS");

        webSocketListener.waitClose();
        client.dispatcher().executorService().shutdown();

    }

}





@Slf4j
class VitoWebSocketListener extends WebSocketListener {
    private static final Logger logger = Logger.getLogger(TutorialService.class.getName());
    private static final int NORMAL_CLOSURE_STATUS = 1000;
    private CountDownLatch latch = null;

    private static void log(Level level, String msg, Object... args) {
        logger.log(level, msg, args);
    }

    @Override
    public void onOpen(WebSocket webSocket, Response response) {
        log(Level.INFO, "Open " + response.message());
        latch = new CountDownLatch(1);
    }

    @Override
    public void onMessage(WebSocket webSocket, String text) {
        System.out.println(text);
        log.info(text);
    }

    @Override
    public void onMessage(WebSocket webSocket, ByteString bytes) {
        System.out.println(bytes.hex());
        log.info(bytes.hex());
    }

    @Override
    public void onClosing(WebSocket webSocket, int code, String reason) {
        webSocket.close(NORMAL_CLOSURE_STATUS, null);
        log(Level.INFO, "Closing {0} {1}", code, reason);
    }

    @Override
    public void onClosed(WebSocket webSocket, int code, String reason) {
        webSocket.close(NORMAL_CLOSURE_STATUS, null);
        log(Level.INFO, "Closed {0} {1}", code, reason);
        latch.countDown();
    }

    @Override
    public void onFailure(WebSocket webSocket, Throwable t, Response response) {
        t.printStackTrace();
        latch.countDown();
    }

    public void waitClose() throws InterruptedException {
        log(Level.INFO, "Wait for finish");
        latch.await();
    }
}
