#pragma once

#include <rtabmap/core/SensorData.h>
#include <QEvent>
#include "data/PerfData.h"
#include "data/Signature.h"

class SignatureEvent :
    public QEvent
{
public:
    // ownership transfer
    SignatureEvent(std::unique_ptr< std::vector<int> > &&wordIds, std::unique_ptr<rtabmap::SensorData> &&sensorData, std::unique_ptr< std::vector<Signature *> > &&signatures, std::unique_ptr<PerfData> &&perfData, const void *session = nullptr);

    std::unique_ptr< std::vector<int> > takeWordIds();
    std::unique_ptr<rtabmap::SensorData> takeSensorData();
    std::unique_ptr< std::vector<Signature *> > takeSignatures();
    std::unique_ptr<PerfData> takePerfData();
    const void *getSession();

    static QEvent::Type type();

private:
    static const QEvent::Type _type;
    std::unique_ptr< std::vector<int> > _wordIds;
    std::unique_ptr<rtabmap::SensorData> _sensorData;
    std::unique_ptr< std::vector<Signature *> > _signatures;
    std::unique_ptr<PerfData> _perfData;
};
