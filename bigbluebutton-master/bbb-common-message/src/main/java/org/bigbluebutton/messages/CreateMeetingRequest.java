package org.bigbluebutton.messages;

import org.bigbluebutton.common.messages.IBigBlueButtonMessage;

public class CreateMeetingRequest implements IBigBlueButtonMessage {
    public final static String NAME = "CreateMeetingRequest";

    public final Header header;
    public final CreateMeetingRequestPayload payload;

    public CreateMeetingRequest(CreateMeetingRequestPayload payload) {
        this.header = new Header(NAME);
        this.payload = payload;
    }

    public static class CreateMeetingRequestPayload {
        public final String id;
        public final String externalId;
        public final String parentId;
        public final String name;
        public final Boolean record;
        public final String voiceConfId;
        public final Integer durationInMinutes;
        public final Boolean autoStartRecording;
        public final Boolean allowStartStopRecording;
        public final Boolean webcamsOnlyForModerator;
        public final String moderatorPassword;
        public final String viewerPassword;
        public final Long createTime;
        public final String createDate;
        public final Boolean isBreakout;
        public final Integer sequence;

        public CreateMeetingRequestPayload(String id, String externalId,
                String parentId, String name, Boolean record,
                String voiceConfId, Integer duration,
                Boolean autoStartRecording, Boolean allowStartStopRecording,
                Boolean webcamsOnlyForModerator, String moderatorPass,
                String viewerPass, Long createTime, String createDate,
                Boolean isBreakout, Integer sequence) {
            this.id = id;
            this.externalId = externalId;
            this.parentId = parentId;
            this.name = name;
            this.record = record;
            this.voiceConfId = voiceConfId;
            this.durationInMinutes = duration;
            this.autoStartRecording = autoStartRecording;
            this.allowStartStopRecording = allowStartStopRecording;
            this.webcamsOnlyForModerator = webcamsOnlyForModerator;
            this.moderatorPassword = moderatorPass;
            this.viewerPassword = viewerPass;
            this.createTime = createTime;
            this.createDate = createDate;
            this.isBreakout = isBreakout;
            this.sequence = sequence;
        }
    }
}
