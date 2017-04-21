/**
 * BigBlueButton open source conferencing system - http://www.bigbluebutton.org/
 *
 * Copyright (c) 2012 BigBlueButton Inc. and by respective authors (see below).
 *
 * This program is free software; you can redistribute it and/or modify it under the
 * terms of the GNU Lesser General Public License as published by the Free Software
 * Foundation; either version 3.0 of the License, or (at your option) any later
 * version.
 *
 * BigBlueButton is distributed in the hope that it will be useful, but WITHOUT ANY
 * WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A
 * PARTICULAR PURPOSE. See the GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License along
 * with BigBlueButton; if not, see <http://www.gnu.org/licenses/>.
 *
 */
package org.bigbluebutton.web.controllers

import org.bigbluebutton.api.responses.InvalidResponse

import javax.servlet.ServletRequest;
import org.apache.commons.codec.binary.Base64;
import org.apache.commons.lang.RandomStringUtils;
import org.apache.commons.lang.StringUtils;
import org.bigbluebutton.api.domain.Config;
import org.bigbluebutton.api.domain.Meeting;
import org.bigbluebutton.api.domain.Recording;
import org.bigbluebutton.api.domain.UserSession;
import org.bigbluebutton.api.ApiErrors;
import org.bigbluebutton.api.ClientConfigService;
import org.bigbluebutton.api.MeetingService;
import org.bigbluebutton.api.ParamsProcessorUtil;
import org.bigbluebutton.api.Util;
import org.bigbluebutton.presentation.PresentationUrlDownloadService;
import org.bigbluebutton.presentation.UploadedPresentation
import org.bigbluebutton.web.services.PresentationService
import org.bigbluebutton.web.services.turn.StunTurnService;
import org.bigbluebutton.web.services.turn.TurnEntry;
import org.json.JSONArray;
import org.json.JSONObject;
import grails.converters.JSON
import grails.converters.XML
import freemarker.template.Configuration;
import freemarker.cache.WebappTemplateLoader;

class ApiController {
  private static final Integer SESSION_TIMEOUT = 14400  // 4 hours
  private static final String CONTROLLER_NAME = 'ApiController'
  private static final String RESP_CODE_SUCCESS = 'SUCCESS'
  private static final String RESP_CODE_FAILED = 'FAILED'
  private static final String ROLE_MODERATOR = "MODERATOR";
  private static final String ROLE_ATTENDEE = "VIEWER";
  private static final String SECURITY_SALT = '639259d4-9dd8-4b25-bf01-95f9567eaf4b'
  private static final String API_VERSION = '0.81'

  MeetingService meetingService;
  PresentationService presentationService
  ParamsProcessorUtil paramsProcessorUtil
  ClientConfigService configService
  PresentationUrlDownloadService presDownloadService
  StunTurnService stunTurnService

  /* general methods */
  def index = {
    log.debug CONTROLLER_NAME + "#index"
    response.addHeader("Cache-Control", "no-cache")
    withFormat {
      xml {
        render(contentType:"text/xml") {
          response() {
            returncode(RESP_CODE_SUCCESS)
            version(paramsProcessorUtil.getApiVersion())
          }
        }
      }
    }
  }

  /*********************************** 
   * BREAKOUT TEST (API) 
   ***********************************/  
  def breakout = {
    if(!StringUtils.isEmpty(params.meetingId)) {
      String meetingId = StringUtils.strip(params.meetingId);
      println("MeetingId = " + meetingId)
    } else {
      println("Missing meetingId")
      return
    }

    if (StringUtils.isEmpty(params.password)) {
      println("Missing password")
      return
    }
  }

  /*********************************** 
   * CREATE (API) 
   ***********************************/
  def create = {
    String API_CALL = 'create'
    log.debug CONTROLLER_NAME + "#${API_CALL}"
    log.debug params

    // BEGIN - backward compatibility
    if (StringUtils.isEmpty(params.checksum)) {
      invalid("checksumError", "You did not pass the checksum security check")
      return
    }

    if(!StringUtils.isEmpty(params.meetingID)) {
      params.meetingID = StringUtils.strip(params.meetingID);
      if (StringUtils.isEmpty(params.meetingID)) {
        invalid("missingParamMeetingID", "You must specify a meeting ID for the meeting.");
        return
      }
    } else {
      invalid("missingParamMeetingID", "You must specify a meeting ID for the meeting.");
      return
    }

    if (! paramsProcessorUtil.isChecksumSame(API_CALL, params.checksum, request.getQueryString())) {
      invalid("checksumError", "You did not pass the checksum security check")
      return
    }
    // END - backward compatibility

    ApiErrors errors = new ApiErrors();
    paramsProcessorUtil.processRequiredCreateParams(params, errors);

    if (errors.hasErrors()) {
      respondWithErrors(errors)
      return
    }

    // Do we agree with the checksum? If not, complain.
    if (! paramsProcessorUtil.isChecksumSame(API_CALL, params.checksum, request.getQueryString())) {
      errors.checksumError()
      respondWithErrors(errors)
      return
    }


    // Translate the external meeting id into an internal meeting id.
    String internalMeetingId = paramsProcessorUtil.convertToInternalMeetingId(params.meetingID);
    Meeting existing = meetingService.getNotEndedMeetingWithId(internalMeetingId);
    if (existing != null) {
      log.debug "Existing conference found"
      Map<String, Object> updateParams = paramsProcessorUtil.processUpdateCreateParams(params);
      if (existing.getViewerPassword().equals(params.get("attendeePW")) && existing.getModeratorPassword().equals(params.get("moderatorPW"))) {
        paramsProcessorUtil.updateMeeting(updateParams, existing);
        // trying to create a conference a second time, return success, but give extra info
        // Ignore pre-uploaded presentations. We only allow uploading of presentation once.
        //uploadDocuments(existing);
        respondWithConference(existing, "duplicateWarning", "This conference was already in existence and may currently be in progress.");
      } else {
        // BEGIN - backward compatibility
        invalid("idNotUnique", "A meeting already exists with that meeting ID.  Please use a different meeting ID.");
        return;
        // END - backward compatibility

        // enforce meetingID unique-ness
        errors.nonUniqueMeetingIdError()
        respondWithErrors(errors)
      }

      return;
    }

    Meeting newMeeting = paramsProcessorUtil.processCreateParams(params);

    if (! StringUtils.isEmpty(params.moderatorOnlyMessage)) {
      newMeeting.setModeratorOnlyMessage(params.moderatorOnlyMessage);
    }

    meetingService.createMeeting(newMeeting);

    // See if the request came with pre-uploading of presentation.
    uploadDocuments(newMeeting);
    respondWithConference(newMeeting, null, null)
  }

  /**********************************************
   * JOIN API
   *********************************************/
  def join = {
    String API_CALL = 'join'
    log.debug CONTROLLER_NAME + "#${API_CALL}"
    ApiErrors errors = new ApiErrors()

    // BEGIN - backward compatibility
    if (StringUtils.isEmpty(params.checksum)) {
      invalid("checksumError", "You did not pass the checksum security check")
      return
    }

    //checking for an empty username or for a username containing whitespaces only
    if(!StringUtils.isEmpty(params.fullName)) {
      params.fullName = StringUtils.strip(params.fullName);
      if (StringUtils.isEmpty(params.fullName)) {
        invalid("missingParamFullName", "You must specify a name for the attendee who will be joining the meeting.");
        return
      }
    } else {
      invalid("missingParamFullName", "You must specify a name for the attendee who will be joining the meeting.");
      return
    }

    if(!StringUtils.isEmpty(params.meetingID)) {
      params.meetingID = StringUtils.strip(params.meetingID);
      if (StringUtils.isEmpty(params.meetingID)) {
        invalid("missingParamMeetingID", "You must specify a meeting ID for the meeting.");
        return
      }
    } else {
      invalid("missingParamMeetingID", "You must specify a meeting ID for the meeting.");
      return
    }

    if (StringUtils.isEmpty(params.password)) {
      invalid("invalidPassword","You either did not supply a password or the password supplied is neither the attendee or moderator password for this conference.");
      return
    }

    if (!paramsProcessorUtil.isChecksumSame(API_CALL, params.checksum, request.getQueryString())) {
      invalid("checksumError", "You did not pass the checksum security check")
      return
    }

    // END - backward compatibility

    // Do we have a checksum? If none, complain.
    if (StringUtils.isEmpty(params.checksum)) {
      errors.missingParamError("checksum");
    }

    // Do we have a name for the user joining? If none, complain.
    if(!StringUtils.isEmpty(params.fullName)) {
      params.fullName = StringUtils.strip(params.fullName);
      if (StringUtils.isEmpty(params.fullName)) {
        errors.missingParamError("fullName");
      }
    } else {
      errors.missingParamError("fullName");
    }
    String fullName = params.fullName

    // Do we have a meeting id? If none, complain.
    if(!StringUtils.isEmpty(params.meetingID)) {
      params.meetingID = StringUtils.strip(params.meetingID);
      if (StringUtils.isEmpty(params.meetingID)) {
        errors.missingParamError("meetingID");
      }
    }
    else {
      errors.missingParamError("meetingID");
    }
    String externalMeetingId = params.meetingID

    // Do we have a password? If not, complain.
    String attPW = params.password
    if (StringUtils.isEmpty(attPW)) {
      errors.missingParamError("password");
    }

    if (errors.hasErrors()) {
      respondWithErrors(errors)
      return
    }

    // Do we agree on the checksum? If not, complain.
    if (! paramsProcessorUtil.isChecksumSame(API_CALL, params.checksum, request.getQueryString())) {
      errors.checksumError()
      respondWithErrors(errors)
      return
    }

    Boolean isBreakoutRoom = false
    if(!StringUtils.isEmpty(params.isBreakout)) {
      isBreakoutRoom = new Boolean(StringUtils.strip(params.isBreakout))
    }

    // Everything is good so far. Translate the external meeting id to an internal meeting id. If
    // we can't find the meeting, complain.
    String internalMeetingId = paramsProcessorUtil.convertToInternalMeetingId(externalMeetingId);
    if (isBreakoutRoom) {
      // This is a join request for a breakout room. Use the passed meetingId to find the meeting.
      internalMeetingId = externalMeetingId
      log.info("Join request for breakout room " + internalMeetingId)
    }

    log.info("Retrieving meeting ${internalMeetingId}")
    Meeting meeting = meetingService.getMeeting(internalMeetingId);
    if (meeting == null) {
      // BEGIN - backward compatibility
      invalid("invalidMeetingIdentifier", "The meeting ID that you supplied did not match any existing meetings");
      return;
      // END - backward compatibility

      errors.invalidMeetingIdError();
      respondWithErrors(errors)
      return;
    }

    // the createTime mismatch with meeting's createTime, complain
    // In the future, the createTime param will be required
    if (params.createTime != null) {
      long createTime = 0;
      try{
        createTime=Long.parseLong(params.createTime);
      } catch(Exception e){
        log.warn("could not parse createTime param");
        createTime = -1;
      }
      if(createTime != meeting.getCreateTime()) {
        errors.mismatchCreateTimeParam();
        respondWithErrors(errors);
        return;
      }
    }

    // Is this user joining a meeting that has been ended. If so, complain.
    if (meeting.isForciblyEnded()) {
      // BEGIN - backward compatibility
      invalid("meetingForciblyEnded", "You can not re-join a meeting that has already been forcibly ended.  However, once the meeting is removed from memory (according to the timeout configured on this server, you will be able to once again create a meeting with the same meeting ID");
      return;
      // END - backward compatibility

      errors.meetingForciblyEndedError();
      respondWithErrors(errors)
      return;
    }

    // Now determine if this user is a moderator or a viewer.
    String role = null;
    if (meeting.getModeratorPassword().equals(attPW)) {
      role = ROLE_MODERATOR;
    } else if (meeting.getViewerPassword().equals(attPW)) {
      role = ROLE_ATTENDEE;
    }

    if (role == null) {
      // BEGIN - backward compatibility
      invalid("invalidPassword","You either did not supply a password or the password supplied is neither the attendee or moderator password for this conference.");
      return
      // END - backward compatibility

      errors.invalidPasswordError()
      respondWithErrors(errors)
      return;
    }

    String webVoice = StringUtils.isEmpty(params.webVoiceConf) ? meeting.getTelVoice() : params.webVoiceConf

    boolean redirectImm = parseBoolean(params.redirectImmediately)

    String internalUserID = RandomStringUtils.randomAlphanumeric(12).toLowerCase()

    String authToken = RandomStringUtils.randomAlphanumeric(12).toLowerCase()
    
    String sessionToken = RandomStringUtils.randomAlphanumeric(16).toLowerCase()

    String externUserID = params.userID
    if (StringUtils.isEmpty(externUserID)) {
      externUserID = internalUserID
    }

    //Return a Map with the user custom data
    Map<String,String> userCustomData = paramsProcessorUtil.getUserCustomData(params);

    //Currently, it's associated with the externalUserID
    if (userCustomData.size() > 0)
      meetingService.addUserCustomData(meeting.getInternalId(), externUserID, userCustomData);

    String configxml = null;

    if (! StringUtils.isEmpty(params.configToken)) {
      Config conf = meeting.getConfig(params.configToken);
      if (conf == null) {
        // Check if this config is one of our pre-built config
        configxml = configService.getConfig(params.configToken)
        if (configxml == null) {
          // Default to the default config.
          configxml = conf.config;
        }
      } else {
        configxml = conf.config;
      }
    } else {
      Config conf = meeting.getDefaultConfig();
      if (conf == null) {
        errors.noConfigFound();
        respondWithErrors(errors);
      } else {
        configxml = conf.config;
      }
    }

    if (StringUtils.isEmpty(configxml)) {
      errors.noConfigFound();
      respondWithErrors(errors);
    }
    UserSession us = new UserSession();
    us.authToken = authToken;
    us.internalUserId = internalUserID
    us.conferencename = meeting.getName()
    us.meetingID = meeting.getInternalId()
    us.externMeetingID = meeting.getExternalId()
    us.externUserID = externUserID
    us.fullname = fullName
    us.role = role
    us.conference = meeting.getInternalId()
    us.room = meeting.getInternalId()
    us.voicebridge = meeting.getTelVoice()
    us.webvoiceconf = meeting.getWebVoice()
    us.mode = "LIVE"
    us.record = meeting.isRecord()
    us.welcome = meeting.getWelcomeMessage()
    us.logoutUrl = meeting.getLogoutUrl();
    us.configXML = configxml;

    if (! StringUtils.isEmpty(params.defaultLayout)) {
      us.defaultLayout = params.defaultLayout;
    }

    if (! StringUtils.isEmpty(params.avatarURL)) {
      us.avatarURL = params.avatarURL;
    } else {
      us.avatarURL = meeting.defaultAvatarURL
    }

    session[sessionToken] = sessionToken
    meetingService.addUserSession(sessionToken, us);

    // Register user into the meeting.
    meetingService.registerUser(us.meetingID, us.internalUserId, us.fullname, us.role, us.externUserID, us.authToken, us.avatarURL)

    log.info("Session user token for " + us.fullname + " [" + session[sessionToken]+ "]")
    session.setMaxInactiveInterval(SESSION_TIMEOUT);

    //check if exists the param redirect
    boolean redirectClient = true;
    String clientURL = paramsProcessorUtil.getDefaultClientUrl();

    if(! StringUtils.isEmpty(params.redirect)) {
      try{
        redirectClient = Boolean.parseBoolean(params.redirect);
      }catch(Exception e){
        redirectClient = true;
      }
    }

    if(!StringUtils.isEmpty(params.clientURL)){
      clientURL = params.clientURL;
    }

    if (redirectClient){
      String destUrl = clientURL + "?sessionToken=" + sessionToken
      log.info("Successfully joined. Redirecting to ${destUrl}");
      redirect(url: destUrl);
    }
    else{
      log.info("Successfully joined. Sending XML response.");
      response.addHeader("Cache-Control", "no-cache")
      withFormat {
        xml {
          render(contentType:"text/xml") {
            response() {
              returncode(RESP_CODE_SUCCESS)
              messageKey("successfullyJoined")
              message("You have joined successfully.")
              meeting_id() { mkp.yield(us.meetingID) }
              user_id(us.internalUserId)
              auth_token(us.authToken)
            }
          }
        }
      }
    }
  }

  /*******************************************
   * IS_MEETING_RUNNING API
   *******************************************/
  def isMeetingRunning = {
    String API_CALL = 'isMeetingRunning'
    log.debug CONTROLLER_NAME + "#${API_CALL}"

    // BEGIN - backward compatibility
    if (StringUtils.isEmpty(params.checksum)) {
      invalid("checksumError", "You did not pass the checksum security check")
      return
    }

    if(!StringUtils.isEmpty(params.meetingID)) {
      params.meetingID = StringUtils.strip(params.meetingID);
      if (StringUtils.isEmpty(params.meetingID)) {
        invalid("missingParamMeetingID", "You must specify a meeting ID for the meeting.");
        return
      }
    } else {
      invalid("missingParamMeetingID", "You must specify a meeting ID for the meeting.");
      return
    }

    if (! paramsProcessorUtil.isChecksumSame(API_CALL, params.checksum, request.getQueryString())) {
      invalid("checksumError", "You did not pass the checksum security check")
      return
    }
    // END - backward compatibility

    ApiErrors errors = new ApiErrors()

    // Do we have a checksum? If none, complain.
    if (StringUtils.isEmpty(params.checksum)) {
      errors.missingParamError("checksum");
    }

    // Do we have a meeting id? If none, complain.
    if(!StringUtils.isEmpty(params.meetingID)) {
      params.meetingID = StringUtils.strip(params.meetingID);
      if (StringUtils.isEmpty(params.meetingID)) {
        errors.missingParamError("meetingID");
      }
    } else {
      errors.missingParamError("meetingID");
    }
    String externalMeetingId = params.meetingID


    if (errors.hasErrors()) {
      respondWithErrors(errors)
      return
    }

    // Do we agree on the checksum? If not, complain.
    if (! paramsProcessorUtil.isChecksumSame(API_CALL, params.checksum, request.getQueryString())) {
      errors.checksumError()
      respondWithErrors(errors)
      return
    }

    // Everything is good so far. Translate the external meeting id to an internal meeting id. If
    // we can't find the meeting, complain.
    String internalMeetingId = paramsProcessorUtil.convertToInternalMeetingId(externalMeetingId);
    log.info("Retrieving meeting ${internalMeetingId}")
    Meeting meeting = meetingService.getMeeting(internalMeetingId);
    boolean isRunning = meeting != null && meeting.isRunning();

    response.addHeader("Cache-Control", "no-cache")
    withFormat {
      xml {
        render(contentType:"text/xml") {
          response() {
            returncode(RESP_CODE_SUCCESS)
            running(isRunning ? "true" : "false")
          }
        }
      }
    }
  }

  /************************************
   * END API
   ************************************/
  def end = {
    String API_CALL = "end"

    log.debug CONTROLLER_NAME + "#${API_CALL}"

    // BEGIN - backward compatibility
    if (StringUtils.isEmpty(params.checksum)) {
      invalid("checksumError", "You did not pass the checksum security check")
      return
    }

    if(!StringUtils.isEmpty(params.meetingID)) {
      params.meetingID = StringUtils.strip(params.meetingID);
      if (StringUtils.isEmpty(params.meetingID)) {
        invalid("missingParamMeetingID", "You must specify a meeting ID for the meeting.");
        return
      }
    } else {
      invalid("missingParamMeetingID", "You must specify a meeting ID for the meeting.");
      return
    }

    if (StringUtils.isEmpty(params.password)) {
      invalid("invalidPassword","You must supply the moderator password for this call.");
      return
    }

    if (! paramsProcessorUtil.isChecksumSame(API_CALL, params.checksum, request.getQueryString())) {
      invalid("checksumError", "You did not pass the checksum security check")
      return
    }
    // END - backward compatibility

    ApiErrors errors = new ApiErrors()

    // Do we have a checksum? If none, complain.
    if (StringUtils.isEmpty(params.checksum)) {
      errors.missingParamError("checksum");
    }

    // Do we have a meeting id? If none, complain.
    if(!StringUtils.isEmpty(params.meetingID)) {
      params.meetingID = StringUtils.strip(params.meetingID);
      if (StringUtils.isEmpty(params.meetingID)) {
        errors.missingParamError("meetingID");
      }
    } else {
      errors.missingParamError("meetingID");
    }
    String externalMeetingId = params.meetingID

    // Do we have a password? If not, complain.
    String modPW = params.password
    if (StringUtils.isEmpty(modPW)) {
      errors.missingParamError("password");
    }

    if (errors.hasErrors()) {
      respondWithErrors(errors)
      return
    }

    // Do we agree on the checksum? If not, complain.
    if (! paramsProcessorUtil.isChecksumSame(API_CALL, params.checksum, request.getQueryString())) {
      errors.checksumError()
      respondWithErrors(errors)
      return
    }

    // Everything is good so far. Translate the external meeting id to an internal meeting id. If
    // we can't find the meeting, complain.
    String internalMeetingId = paramsProcessorUtil.convertToInternalMeetingId(externalMeetingId);
    log.info("Retrieving meeting ${internalMeetingId}")
    Meeting meeting = meetingService.getMeeting(internalMeetingId);
    if (meeting == null) {
      // BEGIN - backward compatibility
      invalid("notFound", "We could not find a meeting with that meeting ID - perhaps the meeting is not yet running?");
      return;
      // END - backward compatibility

      errors.invalidMeetingIdError();
      respondWithErrors(errors)
      return;
    }

    if (meeting.getModeratorPassword().equals(modPW) == false) {
      // BEGIN - backward compatibility
      invalid("invalidPassword","You must supply the moderator password for this call.");
      return;
      // END - backward compatibility

      errors.invalidPasswordError();
      respondWithErrors(errors)
      return;
    }

    meetingService.endMeeting(meeting.getInternalId());

    response.addHeader("Cache-Control", "no-cache")
    withFormat {
      xml {
        render(contentType:"text/xml") {
          response() {
            returncode(RESP_CODE_SUCCESS)
            messageKey("sentEndMeetingRequest")
            message("A request to end the meeting was sent.  Please wait a few seconds, and then use the getMeetingInfo or isMeetingRunning API calls to verify that it was ended.")
          }
        }
      }
    }
  }

  /*****************************************
   * GETMEETINGINFO API
   *****************************************/
  def getMeetingInfo = {
    String API_CALL = "getMeetingInfo"
    log.debug CONTROLLER_NAME + "#${API_CALL}"

    // BEGIN - backward compatibility
    if (StringUtils.isEmpty(params.checksum)) {
      invalid("checksumError", "You did not pass the checksum security check")
      return
    }

    if(!StringUtils.isEmpty(params.meetingID)) {
      params.meetingID = StringUtils.strip(params.meetingID);
      if (StringUtils.isEmpty(params.meetingID)) {
        invalid("missingParamMeetingID", "You must specify a meeting ID for the meeting.");
        return
      }
    } else {
      invalid("missingParamMeetingID", "You must specify a meeting ID for the meeting.");
      return
    }

    if (! paramsProcessorUtil.isChecksumSame(API_CALL, params.checksum, request.getQueryString())) {
      invalid("checksumError", "You did not pass the checksum security check")
      return
    }
    // END - backward compatibility

    ApiErrors errors = new ApiErrors()

    // Do we have a checksum? If none, complain.
    if (StringUtils.isEmpty(params.checksum)) {
      errors.missingParamError("checksum");
    }

    // Do we have a meeting id? If none, complain.
    if(!StringUtils.isEmpty(params.meetingID)) {
      params.meetingID = StringUtils.strip(params.meetingID);
      if (StringUtils.isEmpty(params.meetingID)) {
        errors.missingParamError("meetingID");
      }
    } else {
      errors.missingParamError("meetingID");
    }
    String externalMeetingId = params.meetingID

    if (errors.hasErrors()) {
      respondWithErrors(errors)
      return
    }

    // Do we agree on the checksum? If not, complain.
    if (! paramsProcessorUtil.isChecksumSame(API_CALL, params.checksum, request.getQueryString())) {
      errors.checksumError()
      respondWithErrors(errors)
      return
    }

    // Everything is good so far. Translate the external meeting id to an internal meeting id. If
    // we can't find the meeting, complain.
    String internalMeetingId = paramsProcessorUtil.convertToInternalMeetingId(externalMeetingId);
    log.info("Retrieving meeting ${internalMeetingId}")
    Meeting meeting = meetingService.getMeeting(internalMeetingId);
    if (meeting == null) {
      // BEGIN - backward compatibility
      invalid("notFound", "We could not find a meeting with that meeting ID");
      return;
      // END - backward compatibility

      errors.invalidMeetingIdError();
      respondWithErrors(errors)
      return;
    }

    respondWithConferenceDetails(meeting, null, null, null);
  }

  /************************************
   *  GETMEETINGS API
   ************************************/
  def getMeetingsHandler = {
    String API_CALL = "getMeetings"
    log.debug CONTROLLER_NAME + "#${API_CALL}"

    // BEGIN - backward compatibility
    if (StringUtils.isEmpty(params.checksum)) {
      invalid("checksumError", "You did not pass the checksum security check")
      return
    }

    if (! paramsProcessorUtil.isChecksumSame(API_CALL, params.checksum, request.getQueryString())) {
      invalid("checksumError", "You did not pass the checksum security check")
      return
    }
    // END - backward compatibility

    ApiErrors errors = new ApiErrors()

    // Do we have a checksum? If none, complain.
    if (StringUtils.isEmpty(params.checksum)) {
      errors.missingParamError("checksum");
    }

    if (errors.hasErrors()) {
      respondWithErrors(errors)
      return
    }

    // Do we agree on the checksum? If not, complain.
    if (! paramsProcessorUtil.isChecksumSame(API_CALL, params.checksum, request.getQueryString())) {
      errors.checksumError()
      respondWithErrors(errors)
      return
    }

    Collection<Meeting> mtgs = meetingService.getMeetings();

    if (mtgs == null || mtgs.isEmpty()) {
      response.addHeader("Cache-Control", "no-cache")
      withFormat {
        xml {
          render(contentType:"text/xml") {
            response() {
              returncode(RESP_CODE_SUCCESS)
              meetings()
              messageKey("noMeetings")
              message("no meetings were found on this server")
            }
          }
        }
      }
    } else {
      response.addHeader("Cache-Control", "no-cache")
      withFormat {
        xml {
          render(contentType:"text/xml") {
            response() {
              returncode(RESP_CODE_SUCCESS)
              meetings {
                for (m in mtgs) {
                  meeting {
                    meetingID() { mkp.yield(m.getExternalId()) }
                    isBreakout() { mkp.yield(m.isBreakout()) }
                    meetingName() { mkp.yield(m.getName()) }
                    createTime(m.getCreateTime())
                    createDate(formatPrettyDate(m.getCreateTime()))
                    voiceBridge() { mkp.yield(m.getTelVoice()) }
                    dialNumber() { mkp.yield(m.getDialNumber()) }
                    attendeePW() { mkp.yield(m.getViewerPassword()) }
                    moderatorPW() { mkp.yield(m.getModeratorPassword()) }
                    hasBeenForciblyEnded(m.isForciblyEnded() ? "true" : "false")
                    running(m.isRunning() ? "true" : "false")
                    participantCount(m.getNumUsers())
                    listenerCount(m.getNumListenOnly())
                    voiceParticipantCount(m.getNumVoiceJoined())
                    videoCount(m.getNumVideos())
                    duration(m.duration)
                    hasUserJoined(m.hasUserJoined())
                  }
                }
              }
            }
          }
        }
      }
    }
  }

  /************************************
   *  GETSESSIONS API
   ************************************/
  def getSessionsHandler = {
    String API_CALL = "getSessions"
    log.debug CONTROLLER_NAME + "#${API_CALL}"

    // BEGIN - backward compatibility
    if (StringUtils.isEmpty(params.checksum)) {
      invalid("checksumError", "You did not pass the checksum security check")
      return
    }

    if (! paramsProcessorUtil.isChecksumSame(API_CALL, params.checksum, request.getQueryString())) {
      invalid("checksumError", "You did not pass the checksum security check")
      return
    }
    // END - backward compatibility

    ApiErrors errors = new ApiErrors()

    // Do we have a checksum? If none, complain.
    if (StringUtils.isEmpty(params.checksum)) {
      errors.missingParamError("checksum");
    }

    if (errors.hasErrors()) {
      respondWithErrors(errors)
      return
    }

    // Do we agree on the checksum? If not, complain.
    if (! paramsProcessorUtil.isChecksumSame(API_CALL, params.checksum, request.getQueryString())) {
      errors.checksumError()
      respondWithErrors(errors)
      return
    }

    Collection<Meeting> sssns = meetingService.getSessions();

    if (sssns == null || sssns.isEmpty()) {
      response.addHeader("Cache-Control", "no-cache")
      withFormat {
        xml {
          render(contentType:"text/xml") {
            response() {
              returncode(RESP_CODE_SUCCESS)
              sessions()
              messageKey("noSessions")
              message("no sessions were found on this server")
            }
          }
        }
      }
    } else {
      response.addHeader("Cache-Control", "no-cache")
      withFormat {
        xml {
          render(contentType:"text/xml") {
            response() {
              returncode(RESP_CODE_SUCCESS)
              sessions {
                for (m in sssns) {
                  meeting {
                    meetingID() { mkp.yield(m.meetingID) }
                    meetingName() { mkp.yield(m.conferencename) }
                    userName() { mkp.yield(m.fullname) }
                  }
                }
              }
            }
          }
        }
      }
    }
  }

  
  private Map<String, String[]> getParameters(ServletRequest request) {
    // Copy the parameters into our own Map as we can't pass the paramMap
    // from the request as it's an unmodifiable map.
    Map<String, String[]> reqParams = new HashMap<String, String[]>();
    Map<String, String[]> unModReqParams = request.getParameterMap();

    SortedSet<String> keys = new TreeSet<String>(unModReqParams.keySet());

    for (String key: keys) {
      reqParams.put(key, unModReqParams.get(key));
    }

    return reqParams;
  }

  /***********************************************
   * POLL API
   ***********************************************/
  def setPollXML = {
    String API_CALL = "setPollXML"
    log.debug CONTROLLER_NAME + "#${API_CALL}"

    if (StringUtils.isEmpty(params.checksum)) {
      invalid("checksumError", "You did not pass the checksum security check")
      return
    }

    if (StringUtils.isEmpty(params.pollXML)) {
      invalid("configXMLError", "You did not pass a poll XML")
      return
    }

    if(!StringUtils.isEmpty(params.meetingID)) {
      params.meetingID = StringUtils.strip(params.meetingID);
      if (StringUtils.isEmpty(params.meetingID)) {
        invalid("missingParamMeetingID", "You must specify a meeting ID for the meeting.");
        return
      }
    } else {
      invalid("missingParamMeetingID", "You must specify a meeting ID for the meeting.");
      return
    }

    // Translate the external meeting id into an internal meeting id.
    String internalMeetingId = paramsProcessorUtil.convertToInternalMeetingId(params.meetingID);
    Meeting meeting = meetingService.getMeeting(internalMeetingId);
    if (meeting == null) {
      // BEGIN - backward compatibility
      invalid("invalidMeetingIdentifier", "The meeting ID that you supplied did not match any existing meetings");
      return;
      // END - backward compatibility
    }

    Map<String, String[]> reqParams = getParameters(request)

    String pollXML = params.pollXML

    String decodedPollXML;

    try {
      decodedPollXML = URLDecoder.decode(pollXML, "UTF-8");
    } catch (UnsupportedEncodingException e) {
      log.error("Couldn't decode poll XML.");
      invalid("pollXMLError", "Cannot decode poll XML")
      return;
    }

    if (! paramsProcessorUtil.isPostChecksumSame(API_CALL, reqParams)) {
      response.addHeader("Cache-Control", "no-cache")
      withFormat {
        xml {
          render(contentType:"text/xml") {
            response() {
              returncode("FAILED")
              messageKey("pollXMLChecksumError")
              message("pollXMLChecksumError: request did not pass the checksum security check.")
            }
          }
        }
      }
    } else {

      def pollxml = new XmlSlurper().parseText(decodedPollXML);

      pollxml.children().each { poll ->
        String title = poll.title.text();
        String question = poll.question.text();
        String questionType = poll.questionType.text();

        ArrayList<String> answers = new ArrayList<String>();
        poll.answers.children().each { answer ->
          answers.add(answer.text());
        }

        //send poll to BigBlueButton Apps
        meetingService.createdPolls(meeting.getInternalId(), title, question, questionType, answers);
      }

      response.addHeader("Cache-Control", "no-cache")
      withFormat {
        xml {
          render(contentType:"text/xml") {
            response() { returncode("SUCCESS") }
          }
        }
      }
    }
  }

  /***********************************************
   * CONFIG API
   ***********************************************/
  def setConfigXML = {
    String API_CALL = "setConfigXML"
    log.debug CONTROLLER_NAME + "#${API_CALL}"

    if (StringUtils.isEmpty(params.checksum)) {
      invalid("checksumError", "You did not pass the checksum security check")
      return
    }

    if (StringUtils.isEmpty(params.configXML)) {
      invalid("configXMLError", "You did not pass a config XML")
      return
    }

    if(!StringUtils.isEmpty(params.meetingID)) {
      params.meetingID = StringUtils.strip(params.meetingID);
      if (StringUtils.isEmpty(params.meetingID)) {
        invalid("missingParamMeetingID", "You must specify a meeting ID for the meeting.");
        return
      }
    } else {
      invalid("missingParamMeetingID", "You must specify a meeting ID for the meeting.");
      return
    }

    // Translate the external meeting id into an internal meeting id.
    String internalMeetingId = paramsProcessorUtil.convertToInternalMeetingId(params.meetingID);
    Meeting meeting = meetingService.getMeeting(internalMeetingId);
    if (meeting == null) {
      // BEGIN - backward compatibility
      invalid("invalidMeetingIdentifier", "The meeting ID that you supplied did not match any existing meetings");
      return;
      // END - backward compatibility
    }

    Map<String, String[]> reqParams = getParameters(request)

    String configXML = params.configXML

    String decodedConfigXML;

    try {
      decodedConfigXML = URLDecoder.decode(configXML, "UTF-8");
    } catch (UnsupportedEncodingException e) {
      log.error("Couldn't decode config XML.");
      invalid("configXMLError", "Cannot decode config XML")
      return;
    }

    if (! paramsProcessorUtil.isPostChecksumSame(API_CALL, reqParams)) {
      response.addHeader("Cache-Control", "no-cache")
      withFormat {
        xml {
          render(contentType:"text/xml") {
            response() {
              returncode("FAILED")
              messageKey("configXMLChecksumError")
              message("configXMLChecksumError: request did not pass the checksum security check.")
            }
          }
        }
      }
    } else {
      boolean defaultConfig = false;

      if (! StringUtils.isEmpty(params.defaultConfig)) {
        try {
          defaultConfig = Boolean.parseBoolean(params.defaultConfig);
        } catch(Exception e) {
          defaultConfig = false;
        }
      }

      String token = meeting.storeConfig(defaultConfig, decodedConfigXML);
      response.addHeader("Cache-Control", "no-cache")
      withFormat {
        xml {
          render(contentType:"text/xml") {
            response() {
              returncode("SUCCESS")
              configToken(token)
            }
          }
        }
      }
    }
  }

  /***********************************************
   * CALLBACK API
   ***********************************************/
  def subscribeEvent = {
    String API_CALL = "subscribeEvent"
    log.debug CONTROLLER_NAME + "#${API_CALL}"

    if (StringUtils.isEmpty(params.checksum)) {
      invalid("checksumError", "You did not pass the checksum security check")
      return
    }

    if (StringUtils.isEmpty(params.callbackURL)) {
      invalid("missingParamCallbackURL", "You must specify a callbackURL for subscribing");
      return
    }

    if(!StringUtils.isEmpty(params.meetingID)) {
      params.meetingID = StringUtils.strip(params.meetingID);
      if (StringUtils.isEmpty(params.meetingID)) {
        invalid("missingParamMeetingID", "You must specify a meeting ID for the meeting.");
        return
      }
    } else {
      invalid("missingParamMeetingID", "You must specify a meeting ID for the meeting.");
      return
    }

    String internalMeetingId = paramsProcessorUtil.convertToInternalMeetingId(params.meetingID);
    Meeting meeting = meetingService.getMeeting(internalMeetingId);
    if (meeting == null) {
      // BEGIN - backward compatibility
      invalid("invalidMeetingIdentifier", "The meeting ID that you supplied did not match any existing meetings");
      return;
      // END - backward compatibility

      errors.invalidMeetingIdError();
      respondWithErrors(errors)
      return;
    }

    if (! paramsProcessorUtil.isChecksumSame(API_CALL, params.checksum, request.getQueryString())) {
      response.addHeader("Cache-Control", "no-cache")
      withFormat {
        xml {
          render(contentType:"text/xml") {
            response() {
              returncode("FAILED")
              messageKey("subscribeEventChecksumError")
              message("subscribeEventChecksumError: request did not pass the checksum security check.")
            }
          }
        }
      }
    } else {
      String sid = meetingService.addSubscription(meeting.getInternalId(), meeting.getExternalId(), params.callbackURL);

      if(sid.isEmpty()){
        response.addHeader("Cache-Control", "no-cache")
        withFormat {
          xml {
            render(contentType:"text/xml") {
              response() {
                returncode("FAILED")
                messageKey("subscribeEventError")
                message("subscribeEventError: An error happen while storing your subscription. Check the logs.")
              }
            }
          }
        }

      }else{
        response.addHeader("Cache-Control", "no-cache")
        withFormat {
          xml {
            render(contentType:"text/xml") {
              response() {
                returncode("SUCCESS")
                subscriptionID(sid)
              }
            }
          }
        }
      }
    }
  }

  def unsubscribeEvent = {
    String API_CALL = "unsubscribeEvent"
    log.debug CONTROLLER_NAME + "#${API_CALL}"

    if (StringUtils.isEmpty(params.checksum)) {
      invalid("checksumError", "You did not pass the checksum security check")
      return
    }

    if (StringUtils.isEmpty(params.subscriptionID)) {
      invalid("missingParamSubscriptionID", "You must pass a subscriptionID for unsubscribing")
      return
    }

    if(!StringUtils.isEmpty(params.meetingID)) {
      params.meetingID = StringUtils.strip(params.meetingID);
      if (StringUtils.isEmpty(params.meetingID)) {
        invalid("missingParamMeetingID", "You must specify a meeting ID for the meeting.");
        return
      }
    } else {
      invalid("missingParamMeetingID", "You must specify a meeting ID for the meeting.");
      return
    }

    String internalMeetingId = paramsProcessorUtil.convertToInternalMeetingId(params.meetingID);
    Meeting meeting = meetingService.getMeeting(internalMeetingId);
    if (meeting == null) {
      // BEGIN - backward compatibility
      invalid("invalidMeetingIdentifier", "The meeting ID that you supplied did not match any existing meetings");
      return;
      // END - backward compatibility

      errors.invalidMeetingIdError();
      respondWithErrors(errors)
      return;
    }

    if (! paramsProcessorUtil.isChecksumSame(API_CALL, params.checksum, request.getQueryString())) {
      response.addHeader("Cache-Control", "no-cache")
      withFormat {
        xml {
          render(contentType:"text/xml") {
            response() {
              returncode("FAILED")
              messageKey("unsubscribeEventChecksumError")
              message("unsubscribeEventChecksumError: request did not pass the checksum security check.")
            }
          }
        }
      }
    } else {
      boolean status = meetingService.removeSubscription(meeting.getInternalId(), params.subscriptionID);

      if(!status){
        response.addHeader("Cache-Control", "no-cache")
        withFormat {
          xml {
            render(contentType:"text/xml") {
              response() {
                returncode("FAILED")
                messageKey("unsubscribeEventError")
                message("unsubscribeEventError: An error happen while unsubscribing. Check the logs.")
              }
            }
          }
        }

      }else{
        response.addHeader("Cache-Control", "no-cache")
        withFormat {
          xml {
            render(contentType:"text/xml") {
              response() {
                returncode("SUCCESS")
                unsubscribed(status)
              }
            }
          }
        }
      }
    }
  }

  def listSubscriptions = {
    String API_CALL = "listSubscriptions"
    log.debug CONTROLLER_NAME + "#${API_CALL}"

    if (StringUtils.isEmpty(params.checksum)) {
      invalid("checksumError", "You did not pass the checksum security check")
      return
    }

    if (!StringUtils.isEmpty(params.meetingID)) {
      params.meetingID = StringUtils.strip(params.meetingID);
      if (StringUtils.isEmpty(params.meetingID)) {
        invalid("missingParamMeetingID", "You must specify a meeting ID for the meeting.");
        return
      }
    } else {
      invalid("missingParamMeetingID", "You must specify a meeting ID for the meeting.");
      return
    }

    String internalMeetingId = paramsProcessorUtil.convertToInternalMeetingId(params.meetingID);
    Meeting meeting = meetingService.getMeeting(internalMeetingId);
    if (meeting == null) {
      // BEGIN - backward compatibility
      invalid("invalidMeetingIdentifier", "The meeting ID that you supplied did not match any existing meetings");
      return;
      // END - backward compatibility
    }

    if (!paramsProcessorUtil.isChecksumSame(API_CALL, params.checksum, request.getQueryString())) {
      response.addHeader("Cache-Control", "no-cache")
      withFormat {
        xml {
          render(contentType: "text/xml") {
            response() {
              returncode("FAILED")
              messageKey("listSubscriptionsChecksumError")
              message("listSubscriptionsChecksumError: request did not pass the checksum security check.")
            }
          }
        }
      }
    } else {
      List<Map<String, String>> list = meetingService.listSubscriptions(meeting.getInternalId());

      response.addHeader("Cache-Control", "no-cache")
      withFormat {
        xml {
          render(contentType: "text/xml") {
            response() {
              returncode("SUCCESS")
              subscriptions() {
                list.each { item ->
                  subscription() {
                    subscriptionID() { mkp.yield(item.get("subscriptionID")) }
                    event() { mkp.yield(item.get("event")) }
                    callbackURL() { mkp.yield(item.get("callbackURL")) }
                    active() { mkp.yield(item.get("active")) }
                  }
                }
              }
            }
          }
        }
      }
    }
  }

    def getDefaultConfigXML = {

        String API_CALL = "getDefaultConfigXML"
        ApiErrors errors = new ApiErrors();

        if (StringUtils.isEmpty(params.checksum)) {
            invalid("checksumError", "You did not pass the checksum security check")
            return
        }

        // Do we agree on the checksum? If not, complain.
        if (!paramsProcessorUtil.isChecksumSame(API_CALL, params.checksum, request.getQueryString())) {
            errors.checksumError()
            respondWithErrors(errors)
            return
        }

        String defConfigXML = paramsProcessorUtil.getDefaultConfigXML();

        response.addHeader("Cache-Control", "no-cache")
        render text: defConfigXML, contentType: 'text/xml'
    }


  /***********************************************
   * CONFIG API
   ***********************************************/
  def configXML = {
    String API_CALL = 'configXML'
    log.debug CONTROLLER_NAME + "#${API_CALL}"

    String logoutUrl = paramsProcessorUtil.getDefaultLogoutUrl()
    boolean reject = false
    String sessionToken = null
    UserSession us = null

    if (StringUtils.isEmpty(params.sessionToken)) {
      log.info("No session for user in conference.")
      reject = true
    } else {
      sessionToken = StringUtils.strip(params.sessionToken)
      log.info("SessionToken = " + sessionToken)
      if (!session[sessionToken]) {
          reject = true
      } else {
          us = meetingService.getUserSession(sessionToken);
          if (us == null) reject = true
      }
    }

    if (reject) {
      response.addHeader("Cache-Control", "no-cache")
      withFormat {
        xml {
          render(contentType:"text/xml") {
            response() {
              returncode("FAILED")
              message("Could not find conference.")
              logoutURL() { mkp.yield(logoutUrl) }
            }
          }
        }
      }
    } else {
      response.addHeader("Cache-Control", "no-cache")
      render text: us.configXML, contentType: 'text/xml'
    }
  }

  /***********************************************
   * ENTER API
   ***********************************************/
  def enter = {
    boolean reject = false;
    
    if (StringUtils.isEmpty(params.sessionToken)) {
      println("SessionToken is missing.")
    }
    
    String sessionToken = StringUtils.strip(params.sessionToken)

    UserSession us = null;
    Meeting meeting = null;

    if (!session[sessionToken]) {
      reject = true;
    } else {
      if (meetingService.getUserSession(sessionToken) == null)
        reject = true;
      else {
        us = meetingService.getUserSession(sessionToken);
        meeting = meetingService.getMeeting(us.meetingID, true);
        if (meeting == null || meeting.isForciblyEnded()) {
          reject = true
        }
      }
    }

    if (reject) {
      log.info("No session for user in conference.")

      // Determine the logout url so we can send the user there.
      String logoutUrl = paramsProcessorUtil.getDefaultLogoutUrl()

      if (us != null) {
        logoutUrl = us.logoutUrl
      }

      response.addHeader("Cache-Control", "no-cache")
      withFormat {
        json {
          render(contentType: "application/json") {
            response = {
              returncode = "FAILED"
              message = "Could not find conference."
              logoutURL = logoutUrl
            }
          }
        }
      }
    } else {

      Map<String,String> userCustomData = paramsProcessorUtil.getUserCustomData(params);

      // Generate a new userId for this user. This prevents old connections from
      // removing the user when the user reconnects after being disconnected. (ralam jan 22, 2015)
      // We use underscore (_) to associate userid with the user. We are also able to track
      // how many times a user reconnects or refresh the browser.
      String newInternalUserID = us.internalUserId + "_" + us.incrementConnectionNum()

      log.info("Found conference for " + us.fullname)
      response.addHeader("Cache-Control", "no-cache")
      withFormat {
        json {
          render(contentType: "application/json") {
            response = {
              returncode = "SUCCESS"
              fullname = us.fullname
              confname = us.conferencename
              meetingID = us.meetingID
              externMeetingID = us.externMeetingID
              externUserID = us.externUserID
              internalUserID = newInternalUserID
              authToken = us.authToken
              role = us.role
              conference = us.conference
              room = us.room
              voicebridge = us.voicebridge
              dialnumber = meeting.getDialNumber()
              webvoiceconf = us.webvoiceconf
              mode = us.mode
              record = us.record
              isBreakout = meeting.isBreakout()
              allowStartStopRecording = meeting.getAllowStartStopRecording()
              welcome = us.welcome
              if (! StringUtils.isEmpty(meeting.moderatorOnlyMessage))
                modOnlyMessage = meeting.moderatorOnlyMessage
              logoutUrl = us.logoutUrl
              defaultLayout = us.defaultLayout
              avatarURL = us.avatarURL
              customdata = array {
                userCustomData.each { k, v ->
                  // Somehow we need to prepend something (custdata) for the JSON to work
                  custdata "$k" : v
                }
              }
            }
          }
        }
      }
    }
  }

  /***********************************************
   * STUN/TURN API
   ***********************************************/
  def stuns = {
    boolean reject = false;

    UserSession us = null;
    Meeting meeting = null;
    String sessionToken = null
    
    if (!StringUtils.isEmpty(params.sessionToken)) {
      sessionToken = StringUtils.strip(params.sessionToken)
      println("Session token = [" + sessionToken + "]")
    }
    
    if (!session[sessionToken]) {
      reject = true;
    } else {
      if (meetingService.getUserSession(session[sessionToken]) == null)
        reject = true;
      else {
        us = meetingService.getUserSession(session[sessionToken]);
        meeting = meetingService.getMeeting(us.meetingID);
        if (meeting == null || meeting.isForciblyEnded()) {
          reject = true
        }
      }
    }

    if (reject) {
      log.info("No session for user in conference.")

      String logoutUrl = paramsProcessorUtil.getDefaultLogoutUrl()

      response.addHeader("Cache-Control", "no-cache")
      withFormat {
        json {
          render(contentType: "application/json") {
            response = {
              returncode = "FAILED"
              message = "Could not find conference."
              logoutURL = logoutUrl
            }
          }
        }
      }
    } else {
      Set<String> stuns = stunTurnService.getStunServers()
      Set<TurnEntry> turns = stunTurnService.getStunAndTurnServersFor(us.internalUserId)

      response.addHeader("Cache-Control", "no-cache")
      withFormat {
        json {
          render(contentType: "application/json") {
            stunServers = array {
              stuns.each { stun ->
                stunData = { url = stun.url }
              }
            }
            turnServers = array {
              turns.each { turn ->
                turnData = {
                  username = turn.username
                  password = turn.password
                  url = turn.url
                  ttl = turn.ttl
                }
              }
            }
          }
        }
      }
    }
  }


  /*************************************************
   * SIGNOUT API
   *************************************************/
  def signOut = {

    String sessionToken = null

    if (! StringUtils.isEmpty(params.sessionToken)) {
      sessionToken = StringUtils.strip(params.sessionToken)
      println("SessionToken = " + sessionToken)
    }

    Meeting meeting = null;

    if (sessionToken != null) {
      log.info("Found session for user in conference.")
      UserSession us = meetingService.removeUserSession(sessionToken);
      session.removeAttribute(sessionToken)
    }

    response.addHeader("Cache-Control", "no-cache")
    withFormat {
      xml {
        render(contentType:"text/xml") {
          response() { returncode(RESP_CODE_SUCCESS) }
        }
      }
    }
  }

  /******************************************************
   * GET_RECORDINGS API
   ******************************************************/
  def getRecordingsHandler = {
    String API_CALL = "getRecordings"
    log.debug CONTROLLER_NAME + "#${API_CALL}"

    // BEGIN - backward compatibility
    if (StringUtils.isEmpty(params.checksum)) {
      invalid("checksumError", "You did not pass the checksum security check")
      return
    }

    if (! paramsProcessorUtil.isChecksumSame(API_CALL, params.checksum, request.getQueryString())) {
      invalid("checksumError", "You did not pass the checksum security check")
      return
    }
    // END - backward compatibility

    ApiErrors errors = new ApiErrors()

    // Do we have a checksum? If none, complain.
    if (StringUtils.isEmpty(params.checksum)) {
      errors.missingParamError("checksum");
      respondWithErrors(errors)
      return
    }

    // Do we agree on the checksum? If not, complain.
    if (! paramsProcessorUtil.isChecksumSame(API_CALL, params.checksum, request.getQueryString())) {
      errors.checksumError()
      respondWithErrors(errors)
      return
    }

    List<String> externalMeetingIds = new ArrayList<String>();
    if (!StringUtils.isEmpty(params.meetingID)) {
      externalMeetingIds=paramsProcessorUtil.decodeIds(params.meetingID);
    }

    List<String> internalRecordIds = new ArrayList<String>()
    if (!StringUtils.isEmpty(params.recordID)) {
      internalRecordIds = paramsProcessorUtil.decodeIds(params.recordID)
    }

    List<String> states = new ArrayList<String>()
    if (!StringUtils.isEmpty(params.state)) {
      states = paramsProcessorUtil.decodeIds(params.state)
    }

    // Everything is good so far.
    if ( internalRecordIds.size() == 0 && externalMeetingIds.size() > 0 ) {
      // No recordIDs, process the request based on meetingID(s)
      // Translate the external meeting ids to internal meeting ids (which is the seed for the recordIDs).
      internalRecordIds = paramsProcessorUtil.convertToInternalMeetingId(externalMeetingIds);
    }

    Map<String,Recording> recs = meetingService.getRecordings(internalRecordIds, states);
    recs = meetingService.filterRecordingsByMetadata(recs, ParamsProcessorUtil.processMetaParam(params));

    if (recs.isEmpty()) {
      response.addHeader("Cache-Control", "no-cache")
      withFormat {
        xml {
          render(contentType:"text/xml") {
            response() {
              returncode(RESP_CODE_SUCCESS)
              recordings(null)
              messageKey("noRecordings")
              message("There are not recordings for the meetings")
            }
          }
        }
      }
      return;
    }
    def cfg = new Configuration()

    // Load the XML template
    // TODO: Maybe there is a better way to define the templates path
    def wtl = new WebappTemplateLoader(getServletContext(), "/WEB-INF/freemarker")
    cfg.setTemplateLoader(wtl)
    def ftl = cfg.getTemplate("get-recordings.ftl")
    def xmlText = new StringWriter()
    ftl.process([code:RESP_CODE_SUCCESS, recs:recs.values()], xmlText)
    withFormat {
      xml {
        render(text: xmlText.toString(), contentType: "text/xml")
      }
    }
  }

  /******************************************************
   * PUBLISH_RECORDINGS API
   ******************************************************/

  def publishRecordings = {
    String API_CALL = "publishRecordings"
    log.debug CONTROLLER_NAME + "#${API_CALL}"

    // BEGIN - backward compatibility
    if (StringUtils.isEmpty(params.checksum)) {
      invalid("checksumError", "You did not pass the checksum security check")
      return
    }

    if (StringUtils.isEmpty(params.recordID)) {
      invalid("missingParamRecordID", "You must specify a recordID.");
      return
    }

    if (StringUtils.isEmpty(params.publish)) {
      invalid("missingParamPublish", "You must specify a publish value true or false.");
      return
    }

    if (! paramsProcessorUtil.isChecksumSame(API_CALL, params.checksum, request.getQueryString())) {
      invalid("checksumError", "You did not pass the checksum security check")
      return
    }
    // END - backward compatibility

    ApiErrors errors = new ApiErrors()

    // Do we have a checksum? If none, complain.
    if (StringUtils.isEmpty(params.checksum)) {
      errors.missingParamError("checksum");
    }

    // Do we have a recording id? If none, complain.
    String recordId = params.recordID
    if (StringUtils.isEmpty(recordId)) {
      errors.missingParamError("recordID");
    }
    // Do we have a publish status? If none, complain.
    String publish = params.publish
    if (StringUtils.isEmpty(publish)) {
      errors.missingParamError("publish");
    }

    if (errors.hasErrors()) {
      respondWithErrors(errors)
      return
    }

    // Do we agree on the checksum? If not, complain.
    if (! paramsProcessorUtil.isChecksumSame(API_CALL, params.checksum, request.getQueryString())) {
      errors.checksumError()
      respondWithErrors(errors)
      return
    }

    List<String> recordIdList = new ArrayList<String>();
    if (!StringUtils.isEmpty(recordId)) {
      recordIdList=paramsProcessorUtil.decodeIds(recordId);
    }

    if(!meetingService.existsAnyRecording(recordIdList)){
      // BEGIN - backward compatibility
      invalid("notFound", "We could not find recordings");
      return;
      // END - backward compatibility

    }

    meetingService.setPublishRecording(recordIdList,publish.toBoolean());
    withFormat {
      xml {
        render(contentType:"text/xml") {
          response() {
            returncode(RESP_CODE_SUCCESS)
            published(publish)
          }
        }
      }
    }
  }

  /******************************************************
   * DELETE_RECORDINGS API
   ******************************************************/
  def deleteRecordings = {
    String API_CALL = "deleteRecordings"
    log.debug CONTROLLER_NAME + "#${API_CALL}"

    // BEGIN - backward compatibility
    if (StringUtils.isEmpty(params.checksum)) {
      invalid("checksumError", "You did not pass the checksum security check")
      return
    }

    if (StringUtils.isEmpty(params.recordID)) {
      invalid("missingParamRecordID", "You must specify a recordID.");
      return
    }

    if (! paramsProcessorUtil.isChecksumSame(API_CALL, params.checksum, request.getQueryString())) {
      invalid("checksumError", "You did not pass the checksum security check")
      return
    }
    // END - backward compatibility

    ApiErrors errors = new ApiErrors()

    // Do we have a checksum? If none, complain.
    if (StringUtils.isEmpty(params.checksum)) {
      errors.missingParamError("checksum");
    }

    // Do we have a recording id? If none, complain.
    String recordId = params.recordID
    if (StringUtils.isEmpty(recordId)) {
      errors.missingParamError("recordID");
    }

    if (errors.hasErrors()) {
      respondWithErrors(errors)
      return
    }

    // Do we agree on the checksum? If not, complain.
    if (! paramsProcessorUtil.isChecksumSame(API_CALL, params.checksum, request.getQueryString())) {
      errors.checksumError()
      respondWithErrors(errors)
      return
    }

    ArrayList<String> recordIdList = new ArrayList<String>();
    if (!StringUtils.isEmpty(recordId)) {
      recordIdList=paramsProcessorUtil.decodeIds(recordId);
    }

    if(recordIdList.isEmpty()){
      // BEGIN - backward compatibility
      invalid("notFound", "We could not find recordings");
      return;
      // END - backward compatibility
    }

    meetingService.deleteRecordings(recordIdList);
    withFormat {
      xml {
        render(contentType:"text/xml") {
          response() {
            returncode(RESP_CODE_SUCCESS)
            deleted(true)
          }
        }
      }
    }
  }

  def uploadDocuments(conf) {
    log.debug("ApiController#uploadDocuments(${conf.getInternalId()})");

    String requestBody = request.inputStream == null ? null : request.inputStream.text;
    requestBody = StringUtils.isEmpty(requestBody) ? null : requestBody;

    if (requestBody == null) {
      downloadAndProcessDocument(presentationService.defaultUploadedPresentation, conf.getInternalId());
    } else {
      log.debug "Request body: \n" + requestBody;
      def xml = new XmlSlurper().parseText(requestBody);
      xml.children().each { module ->
        log.debug("module config found: [${module.@name}]");

        if ("presentation".equals(module.@name.toString())) {
          // need to iterate over presentation files and process them
          module.children().each { document ->
            if (!StringUtils.isEmpty(document.@url.toString())) {
              downloadAndProcessDocument(document.@url.toString(), conf.getInternalId());
            } else if (!StringUtils.isEmpty(document.@name.toString())) {
              def b64 = new Base64()
              def decodedBytes = b64.decode(document.text().getBytes())
              processDocumentFromRawBytes(decodedBytes, document.@name.toString(), conf.getInternalId());
            } else {
              log.debug("presentation module config found, but it did not contain url or name attributes");
            }
          }
        }
      }
    }
  }


  def processDocumentFromRawBytes(bytes, presFilename, meetingId) {
    def filenameExt = Util.getFilenameExt(presFilename);
    String presentationDir = presentationService.getPresentationDir()
    def presId = Util.generatePresentationId(presFilename)
    File uploadDir = Util.createPresentationDirectory(meetingId, presentationDir, presId)
    if (uploadDir != null) {
      def newFilename = Util.createNewFilename(presId, filenameExt)
      def pres = new File(uploadDir.absolutePath + File.separatorChar + newFilename);

      FileOutputStream fos = new java.io.FileOutputStream(pres)
      fos.write(bytes)
      fos.flush()
      fos.close()

      processUploadedFile(meetingId, presId, presFilename, pres);
    }

  }

  def downloadAndProcessDocument(address, meetingId) {
    log.debug("ApiController#downloadAndProcessDocument(${address}, ${meetingId})");
    String presFilename = address.tokenize("/")[-1];
    def filenameExt = presDownloadService.getFilenameExt(presFilename);
    String presentationDir = presentationService.getPresentationDir()

    def presId = presDownloadService.generatePresentationId(presFilename)
    File uploadDir = presDownloadService.createPresentationDirectory(meetingId, presentationDir, presId)
    if (uploadDir != null) {
      def newFilename = presDownloadService.createNewFilename(presId, filenameExt)
      def newFilePath = uploadDir.absolutePath + File.separatorChar + newFilename

      if (presDownloadService.savePresentation(meetingId, newFilePath, address)) {
        def pres = new File(newFilePath)
        processUploadedFile(meetingId, presId, presFilename, pres);
      } else {
        log.error("Failed to download presentation=[${address}], meeting=[${meetingId}]")
      }
    }
  }


  def processUploadedFile(meetingId, presId, filename, presFile) {
    def presentationBaseUrl = presentationService.presentationBaseUrl
    UploadedPresentation uploadedPres = new UploadedPresentation(meetingId, presId, filename, presentationBaseUrl);
    uploadedPres.setUploadedFile(presFile);
    presentationService.processUploadedPresentation(uploadedPres);
  }

  def beforeInterceptor = {
    if (paramsProcessorUtil.isServiceEnabled() == false) {
      log.info("apiNotEnabled: The API service and/or controller is not enabled on this server.  To use it, you must first enable it.")
      // TODO: this doesn't stop the request - so it generates invalid XML
      //      since the request continues and renders a second response
      invalid("apiNotEnabled", "The API service and/or controller is not enabled on this server.  To use it, you must first enable it.")
    }
  }

  def formatPrettyDate(timestamp) {
    //    SimpleDateFormat ft = new SimpleDateFormat ("E yyyy.MM.dd 'at' hh:mm:ss a zzz");
    //    return ft.format(new Date(timestamp))

    return new Date(timestamp).toString()
  }

  def respondWithConferenceDetails(meeting, room, msgKey, msg) {
    def createdOn = formatPrettyDate(meeting.getCreateTime())

    def messageKey = ""
    if (!StringUtils.isEmpty(msgKey)) {
      messageKey = msgKey
    }

    def message = ""
    if (!StringUtils.isEmpty(msg)) {
      message = msg
    }

    def cfg = new Configuration()
    // Load the XML template
    // TODO: Maybe there is a better way to define the templates path
    def wtl = new WebappTemplateLoader(getServletContext(), "/WEB-INF/freemarker")
    cfg.setTemplateLoader(wtl)
    def ftl = cfg.getTemplate("respond-with-conference-details.ftl")
    def xmlText = new StringWriter()
    ftl.process([returnCode: RESP_CODE_SUCCESS, messageKey: messageKey,
                 message: message, createdOn: createdOn, meeting: meeting], xmlText)


    response.addHeader("Cache-Control", "no-cache")
    withFormat {
      xml {
        render(text: xmlText.toString(), contentType: "text/xml")
      }
    }
  }

  def respondWithConference(meeting, msgKey, msg) {

    def createdOn = formatPrettyDate(meeting.getCreateTime())

    def messageKey = ""
    if (!StringUtils.isEmpty(msgKey)) {
      messageKey = msgKey
    }

    def message = ""
    if (!StringUtils.isEmpty(msg)) {
      message = msg
    }

    def cfg = new Configuration()
    // Load the XML template
    // TODO: Maybe there is a better way to define the templates path
    def wtl = new WebappTemplateLoader(getServletContext(), "/WEB-INF/freemarker")
    cfg.setTemplateLoader(wtl)
    def ftl = cfg.getTemplate("respond-with-conference.ftl")
    def xmlText = new StringWriter()
    ftl.process([returnCode: RESP_CODE_SUCCESS, messageKey: messageKey,
                 message: message, createdOn: createdOn, meeting: meeting], xmlText)

    response.addHeader("Cache-Control", "no-cache")
    withFormat {
      xml {
        render(text: xmlText.toString(), contentType: "text/xml")
      }
    }
  }

  def respondWithErrors(errorList) {
    log.debug CONTROLLER_NAME + "#invalid"
    response.addHeader("Cache-Control", "no-cache")
    withFormat {
      xml {
        render(contentType:"text/xml") {
          response1() {
            returncode(RESP_CODE_FAILED)
            errors() {
              ArrayList errs = errorList.getErrors();
              Iterator itr = errs.iterator();
              while (itr.hasNext()){
                String[] er = (String[]) itr.next();
                log.debug CONTROLLER_NAME + "#invalid" + er[0]
                error(key: er[0], message: er[1])
              }
            }
          }
        }
      }
      json {
        log.debug "Rendering as json"
        render(contentType:"text/json") {
          returncode(RESP_CODE_FAILED)
          messageKey(key)
          message(msg)
        }
      }
    }
  }
  //TODO: method added for backward compability, it will be removed in next versions after 0.8
  def invalid(key, msg) {
    String deprecatedMsg=" Note: This xml scheme will be DEPRECATED."
    log.debug CONTROLLER_NAME + "#invalid"
    InvalidResponse invalidResponse = new InvalidResponse(RESP_CODE_FAILED, key, msg);

    def cfg = new Configuration()
    // Load the XML template
    // TODO: Maybe there is a better way to define the templates path
    def wtl = new WebappTemplateLoader(getServletContext(), "/WEB-INF/freemarker")
    cfg.setTemplateLoader(wtl)
    def ftl = cfg.getTemplate("invalid-response.ftl")
    def xmlText = new StringWriter()
    ftl.process([returnCode: invalidResponse.returnCode, messageKey: invalidResponse.messageKey,
                 message: invalidResponse.message], xmlText)

    response.addHeader("Cache-Control", "no-cache")
    withFormat {
      xml {
        render(text: xmlText.toString(), contentType: "text/xml")
      }
      json {
        log.debug "Rendering as json"
        render(contentType:"text/json") {
          returncode(RESP_CODE_FAILED)
          messageKey(key)
          message(msg)
        }
      }
    }
  }

  def parseBoolean(obj) {
    if (obj instanceof Number) {
      return ((Number) obj).intValue() == 1;
    }
    return false
  }

}
