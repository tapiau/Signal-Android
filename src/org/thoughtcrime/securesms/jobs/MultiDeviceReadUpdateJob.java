package org.thoughtcrime.securesms.jobs;

import android.content.Context;
import android.support.annotation.Keep;

import com.fasterxml.jackson.annotation.JsonProperty;

import org.thoughtcrime.securesms.logging.Log;

import org.thoughtcrime.securesms.crypto.MasterSecret;
import org.thoughtcrime.securesms.database.MessagingDatabase.SyncMessageId;
import org.thoughtcrime.securesms.dependencies.InjectableType;
import org.thoughtcrime.securesms.jobmanager.JobParameters;
import org.thoughtcrime.securesms.jobmanager.requirements.NetworkRequirement;
import org.thoughtcrime.securesms.jobs.requirements.MasterSecretRequirement;
import org.thoughtcrime.securesms.util.JsonUtils;
import org.thoughtcrime.securesms.util.TextSecurePreferences;
import org.whispersystems.signalservice.api.SignalServiceMessageSender;
import org.whispersystems.signalservice.api.crypto.UntrustedIdentityException;
import org.whispersystems.signalservice.api.messages.multidevice.ReadMessage;
import org.whispersystems.signalservice.api.messages.multidevice.SignalServiceSyncMessage;
import org.whispersystems.signalservice.api.push.exceptions.PushNetworkException;

import java.io.IOException;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;

import javax.inject.Inject;

import androidx.work.Data;

public class MultiDeviceReadUpdateJob extends MasterSecretJob implements InjectableType {

  private static final long serialVersionUID = 1L;
  private static final String TAG = MultiDeviceReadUpdateJob.class.getSimpleName();

  private static final String KEY_MESSAGE_IDS = "message_ids";

  private List<SerializableSyncMessageId> messageIds;

  @Inject transient SignalServiceMessageSender messageSender;

  @Keep
  public MultiDeviceReadUpdateJob() {
    super(null, null);
  }

  public MultiDeviceReadUpdateJob(Context context, List<SyncMessageId> messageIds) {
    super(context, JobParameters.newBuilder()
                                .withRequirement(new NetworkRequirement(context))
                                .withRequirement(new MasterSecretRequirement(context))
                                .create());

    this.messageIds = new LinkedList<>();

    for (SyncMessageId messageId : messageIds) {
      this.messageIds.add(new SerializableSyncMessageId(messageId.getAddress().toPhoneString(), messageId.getTimetamp()));
    }
  }

  @Override
  protected void initialize(Data data) {
    String[] ids = data.getStringArray(KEY_MESSAGE_IDS);
    assert ids != null;

    messageIds = new ArrayList<>(ids.length);
    for (String id : ids) {
      try {
        messageIds.add(JsonUtils.fromJson(id, SerializableSyncMessageId.class));
      } catch (IOException e) {
        throw new AssertionError(e);
      }
    }
  }

  @Override
  protected Data serialize(Data.Builder dataBuilder) {
    String[] ids = new String[messageIds.size()];

    for (int i = 0; i < ids.length; i++) {
      try {
        ids[i] = JsonUtils.toJson(messageIds.get(i));
      } catch (IOException e) {
        throw new AssertionError(e);
      }
    }

    return dataBuilder.putStringArray(KEY_MESSAGE_IDS, ids).build();
  }

  @Override
  public void onRun(MasterSecret masterSecret) throws IOException, UntrustedIdentityException {
    if (!TextSecurePreferences.isMultiDevice(context)) {
      Log.w(TAG, "Not multi device...");
      return;
    }

    List<ReadMessage> readMessages = new LinkedList<>();

    for (SerializableSyncMessageId messageId : messageIds) {
      readMessages.add(new ReadMessage(messageId.sender, messageId.timestamp));
    }

    messageSender.sendMessage(SignalServiceSyncMessage.forRead(readMessages));
  }

  @Override
  public boolean onShouldRetryThrowable(Exception exception) {
    return exception instanceof PushNetworkException;
  }

  @Override
  public void onCanceled() {

  }

  private static class SerializableSyncMessageId implements Serializable {

    private static final long serialVersionUID = 1L;

    @JsonProperty
    private final String sender;

    @JsonProperty
    private final long   timestamp;

    private SerializableSyncMessageId(@JsonProperty("sender") String sender, @JsonProperty("timestamp") long timestamp) {
      this.sender = sender;
      this.timestamp = timestamp;
    }
  }
}
