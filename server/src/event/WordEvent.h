#pragma once

#include "data/PerfData.h"
#include "data/SensorData.h"
#include <QEvent>
#include <memory>

class WordEvent : public QEvent {
public:
  WordEvent(std::unique_ptr<std::vector<int>> &&wordIds,
            std::unique_ptr<SensorData> &&sensorData,
            std::unique_ptr<PerfData> &&perfData, const void *session);

  std::unique_ptr<std::vector<int>> takeWordIds();
  std::unique_ptr<SensorData> takeSensorData();
  std::unique_ptr<PerfData> takePerfData();
  const void *getSession();

  static QEvent::Type type();

private:
  static const QEvent::Type _type;
  std::unique_ptr<std::vector<int>> _wordIds;
  std::unique_ptr<SensorData> _sensorData;
  std::unique_ptr<PerfData> _perfData;
  const void *_session;
};
