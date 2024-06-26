package com.artemchep.keyguard.common.usecase

import com.artemchep.keyguard.common.io.IO
import com.artemchep.keyguard.common.service.justgetmydata.JustGetMyDataServiceInfo

interface GetJustGetMyDataByUrl : (String) -> IO<JustGetMyDataServiceInfo?>
