package org.blugento.common.amq.blob.spring;

import lombok.Getter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collection;
import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

class BlobEntry {
  private static final Logger log = LoggerFactory.getLogger(BlobEntry.class);

  @Getter
  private final Path path;

  private final Collection<String> ids = new CopyOnWriteArraySet<>();
  private final long ttl;
  private final Consumer<BlobEntry> onDeletion;
  private final AtomicInteger expectedDownloads = new AtomicInteger(0);
  private final AtomicInteger doneDownloads = new AtomicInteger(0);
  private final AtomicInteger runningDownloads = new AtomicInteger(0);
  private final AtomicBoolean deleted = new AtomicBoolean(false);

  private final Timer timer = new Timer();

  BlobEntry(Path path, long ttl, Consumer<BlobEntry> onDeletion) {
    this.path = path;
    this.ttl = ttl;
    this.onDeletion = onDeletion;
  }

  private void scheduleDeletion() {
    timer.cancel();

    final int expected = expectedDownloads.get();
    timer.schedule(new TimerTask() {
      @Override
      public void run() {
        if (expected == expectedDownloads.get() && runningDownloads.get() == 0 && !deleted.get()) {
          delete();
        }
      }
    }, ttl * 1000, ttl * 1000);
  }

  void expectDownloads(String id, int expectedDownloads) {
    ids.add(id);
    this.expectedDownloads.addAndGet(expectedDownloads);
    this.scheduleDeletion();
  }

  boolean hasId(String id) {
    return ids.contains(id);
  }

  void markRunning() {
    runningDownloads.incrementAndGet();
  }

  void markDone() {
    if (doneDownloads.incrementAndGet() == expectedDownloads.get()) {
      delete();
    }
    runningDownloads.decrementAndGet();
  }

  void delete() {
    deleteAndRemove(true);
  }

  void remove() {
    deleteAndRemove(false);
  }

  private void deleteAndRemove(boolean delete) {
    if (deleted.get()) {
      return;
    }
    deleted.set(true);
    try {
      if (delete) {
        Files.delete(path);
      }
      onDeletion.accept(this);
      timer.cancel();
      log.error("Deleted {}", path);
    } catch (IOException e) {
      log.error("Error while deleting {}", path, e);
      deleted.set(false);
    }
  }
}
