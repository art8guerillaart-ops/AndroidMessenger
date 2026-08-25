package com.example.messenger.data.api

import com.example.messenger.data.model.*
import okhttp3.MultipartBody
import okhttp3.ResponseBody
import retrofit2.Response
import retrofit2.http.*

interface ApiService {

    // ---- Auth ----
    @POST("/api/send-code")
    suspend fun sendCode(@Body body: EmailRequest): Response<SimpleMessage>

    @POST("/api/verify-code")
    suspend fun verifyCode(@Body body: VerifyRequest): Response<AuthResponse>

    @POST("/api/logout")
    suspend fun logout(@Header("X-Session-Token") token: String): Response<SimpleMessage>

    // ---- Profile ----
    @GET("/api/profile")
    suspend fun getProfile(@Header("X-Session-Token") token: String): Response<ProfileDto>

    @PATCH("/api/profile")
    suspend fun updateProfile(
        @Header("X-Session-Token") token: String,
        @Body body: NicknameUpdateRequest
    ): Response<ProfileDto>

    // ---- Chats ----
    @GET("/api/chats")
    suspend fun getChats(@Header("X-Session-Token") token: String): Response<List<ChatDto>>

    @POST("/api/dm")
    suspend fun startDm(
        @Header("X-Session-Token") token: String,
        @Body body: DmRequest
    ): Response<Map<String, String>>

    @POST("/api/chats")
    suspend fun createChat(
        @Header("X-Session-Token") token: String,
        @Body body: ChatCreateRequest
    ): Response<Map<String, String>>

    @GET("/api/chats/{chatId}/participants")
    suspend fun getParticipants(
        @Header("X-Session-Token") token: String,
        @Path("chatId") chatId: String
    ): Response<List<ParticipantDto>>

    @POST("/api/chats/{chatId}/participants")
    suspend fun addParticipant(
        @Header("X-Session-Token") token: String,
        @Path("chatId") chatId: String,
        @Body body: ParticipantRequest
    ): Response<SimpleMessage>

    @DELETE("/api/chats/{chatId}/participants/{memberEmail}")
    suspend fun removeParticipant(
        @Header("X-Session-Token") token: String,
        @Path("chatId") chatId: String,
        @Path("memberEmail") memberEmail: String
    ): Response<SimpleMessage>

    @DELETE("/api/chats/{chatId}")
    suspend fun deleteChat(
        @Header("X-Session-Token") token: String,
        @Path("chatId") chatId: String
    ): Response<SimpleMessage>

    // ---- Messages ----
    @GET("/api/messages/{chatId}")
    suspend fun getMessages(
        @Header("X-Session-Token") token: String,
        @Path("chatId") chatId: String,
        @Query("limit") limit: Int = 100,
        @Query("before_id") beforeId: Int? = null
    ): Response<List<MessageDto>>

    // ---- Upload ----
    @Multipart
    @POST("/api/upload")
    suspend fun uploadFile(
        @Header("X-Session-Token") token: String,
        @Part file: MultipartBody.Part
    ): Response<UploadResponse>

    // ---- Signal Protocol keys (E2E для dm) ----
    @POST("/api/keys/publish")
    suspend fun publishKeys(
        @Header("X-Session-Token") token: String,
        @Body body: PublishKeysRequest
    ): Response<SimpleMessage>

    @GET("/api/keys/bundle/{email}")
    suspend fun getKeyBundle(
        @Header("X-Session-Token") token: String,
        @Path("email") email: String
    ): Response<KeyBundleResponse>

    @GET("/api/keys/count")
    suspend fun getKeyCount(@Header("X-Session-Token") token: String): Response<KeyCountResponse>
}
