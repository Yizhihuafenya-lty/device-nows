import systemInfo from '@ohos.systemInfo';
import batteryInfo from '@ohos.batteryInfo';

interface MemorySnapshot {
  total: number;
  available: number;
  used: number;
  percent: number;
}

interface BatterySnapshot {
  voltage: number;
  current: number;
  power: number;
}

@Entry
@Component
struct Index {
  @State selectedTab: number = 0;
  @State deviceName: string = 'HarmonyOS 设备';
  @State osVersion: string = 'HarmonyOS';
  @State processorCount: number = 0;
  @State memory: MemorySnapshot = { total: 0, available: 0, used: 0, percent: 0 };
  @State battery: BatterySnapshot = { voltage: 0, current: 0, power: 0 };
  @State powerPoints: Array<number> = [];
  @State lastUpdate: string = '等待采样';
  @State cpuNotice: string = 'CPU 逐核心占用：系统开放能力检测中';
  private timer: number = -1;
  private chartContext: CanvasRenderingContext2D = new CanvasRenderingContext2D(new RenderingContextSettings(true));

  aboutToAppear(): void {
    this.loadStaticInfo();
    this.sample();
    this.timer = setInterval(() => this.sample(), 1000);
  }

  aboutToDisappear(): void {
    if (this.timer >= 0) {
      clearInterval(this.timer);
      this.timer = -1;
    }
  }

  private loadStaticInfo(): void {
    try {
      this.deviceName = `${systemInfo.manufacturer} ${systemInfo.productModel}`.trim();
      this.osVersion = `${systemInfo.osFullName} · API ${systemInfo.sdkApiVersion}`;
      this.processorCount = systemInfo.processorCount;
    } catch (_) {
      this.cpuNotice = 'CPU 信息暂时不可读取';
    }
  }

  private sample(): void {
    this.readMemory();
    this.readBattery();
    this.lastUpdate = new Date().toLocaleTimeString();
  }

  private readMemory(): void {
    try {
      const info = systemInfo.getSystemMemoryInfoSync();
      const total = Number(info.totalMem);
      const available = Number(info.availMem);
      const used = Math.max(total - available, 0);
      this.memory = {
        total: total,
        available: available,
        used: used,
        percent: total > 0 ? used / total * 100 : 0
      };
    } catch (_) {
      this.memory = { total: 0, available: 0, used: 0, percent: 0 };
    }
  }

  private readBattery(): void {
    try {
      const voltage = Number(batteryInfo.voltage) / 1000;
      const current = Number(batteryInfo.currentNow);
      const power = voltage > 0 && Number.isFinite(current) ? voltage * Math.abs(current) / 1000 : 0;
      this.battery = { voltage: voltage, current: current, power: power };
      if (power > 0) {
        const next = this.powerPoints.slice();
        if (next.length >= 60) {
          next.shift();
        }
        next.push(power);
        this.powerPoints = next;
        this.drawChart();
      }
    } catch (_) {
      this.battery = { voltage: 0, current: 0, power: 0 };
    }
  }

  private formatBytes(value: number): string {
    if (value <= 0) return '未知';
    const gb = value / 1024 / 1024 / 1024;
    return gb >= 1 ? `${gb.toFixed(2)} GB` : `${(value / 1024 / 1024).toFixed(0)} MB`;
  }

  private drawChart(): void {
    const width = 640;
    const height = 220;
    const left = 52;
    const top = 18;
    const right = width - 16;
    const bottom = height - 28;
    let maxValue = 1;
    this.powerPoints.forEach((value: number) => {
      maxValue = Math.max(maxValue, value);
    });

    this.chartContext.clearRect(0, 0, width, height);
    this.chartContext.strokeStyle = '#E2E4EF';
    this.chartContext.lineWidth = 1;
    for (let i = 0; i <= 4; i++) {
      const y = top + (bottom - top) * i / 4;
      this.chartContext.beginPath();
      this.chartContext.moveTo(left, y);
      this.chartContext.lineTo(right, y);
      this.chartContext.stroke();
    }
    if (this.powerPoints.length < 2) return;
    this.chartContext.strokeStyle = '#4F46E5';
    this.chartContext.lineWidth = 4;
    this.chartContext.beginPath();
    this.powerPoints.forEach((value: number, index: number) => {
      const x = left + (right - left) * index / (this.powerPoints.length - 1);
      const y = bottom - value / maxValue * (bottom - top);
      if (index === 0) this.chartContext.moveTo(x, y);
      else this.chartContext.lineTo(x, y);
    });
    this.chartContext.stroke();
  }

  @Builder
  private header(): void {
    Row() {
      Column() {
        Text('设备信息')
          .fontSize(13)
          .fontColor('#C7D2FE')
        Text(this.deviceName)
          .fontSize(24)
          .fontWeight(FontWeight.Bold)
          .fontColor(Color.White)
        Text(this.osVersion)
          .fontSize(13)
          .fontColor('#DDE4FF')
          .margin({ top: 4 })
      }
      .alignItems(HorizontalAlign.Start)
      .layoutWeight(1)
      Text('实时')
        .fontSize(12)
        .fontColor('#3730A3')
        .backgroundColor(Color.White)
        .borderRadius(16)
        .padding({ left: 12, right: 12, top: 7, bottom: 7 })
    }
    .width('100%')
    .padding(20)
    .backgroundColor('#4F46E5')
    .borderRadius(24)
  }

  @Builder
  private metricCard(title: string, value: string, detail: string, percent: number): void {
    Column() {
      Row() {
        Text(title).fontSize(16).fontWeight(FontWeight.Bold).fontColor('#1F2937').layoutWeight(1)
        Text(value).fontSize(18).fontWeight(FontWeight.Bold).fontColor('#4F46E5')
      }
      Text(detail).fontSize(13).fontColor('#64748B').margin({ top: 8 }).width('100%')
      Progress({ value: Math.min(Math.max(percent, 0), 100), total: 100, type: ProgressType.Linear })
        .width('100%')
        .height(8)
        .margin({ top: 12 })
        .color('#4F46E5')
        .backgroundColor('#E5E7EB')
    }
    .width('100%')
    .padding(18)
    .backgroundColor(Color.White)
    .borderRadius(18)
    .shadow({ radius: 12, color: '#15000000', offsetY: 4, offsetX: 0 })
  }

  @Builder
  private batteryCard(): void {
    Column() {
      Row() {
        Text('电池实时状态').fontSize(16).fontWeight(FontWeight.Bold).layoutWeight(1)
        Text(`${this.battery.power.toFixed(3)} W`).fontSize(18).fontWeight(FontWeight.Bold).fontColor('#0F766E')
      }
      Row() {
        this.batteryItem('电压', `${this.battery.voltage.toFixed(3)} V`)
        this.batteryItem('电流', `${this.battery.current.toFixed(1)} mA`)
      }
      .margin({ top: 16 })
      .width('100%')
      Text('功率曲线（最近 60 秒）').fontSize(13).fontColor('#64748B').margin({ top: 16 }).width('100%')
      Canvas(this.chartContext)
        .width('100%')
        .height(180)
        .backgroundColor('#F8FAFC')
        .borderRadius(12)
        .onReady(() => this.drawChart())
      Text('每秒刷新 · 最后更新 ' + this.lastUpdate).fontSize(12).fontColor('#94A3B8').margin({ top: 8 })
    }
    .width('100%')
    .padding(18)
    .backgroundColor(Color.White)
    .borderRadius(18)
    .shadow({ radius: 12, color: '#15000000', offsetY: 4, offsetX: 0 })
  }

  @Builder
  private batteryItem(label: string, value: string): void {
    Column() {
      Text(label).fontSize(12).fontColor('#64748B')
      Text(value).fontSize(16).fontWeight(FontWeight.Medium).fontColor('#1F2937').margin({ top: 4 })
    }
    .alignItems(HorizontalAlign.Start)
    .layoutWeight(1)
  }

  @Builder
  private cpuCard(): void {
    Column() {
      Row() {
        Text('处理器').fontSize(16).fontWeight(FontWeight.Bold).layoutWeight(1)
        Text(`${this.processorCount} 逻辑线程`).fontSize(14).fontColor('#4F46E5')
      }
      Text(this.cpuNotice).fontSize(13).fontColor('#64748B').margin({ top: 12 }).width('100%')
      Text('逐核心真实占用需要设备开放对应系统能力；当前页面不会用虚假数据填充。')
        .fontSize(12)
        .fontColor('#94A3B8')
        .margin({ top: 8 })
        .width('100%')
    }
    .width('100%')
    .padding(18)
    .backgroundColor(Color.White)
    .borderRadius(18)
    .shadow({ radius: 12, color: '#15000000', offsetY: 4, offsetX: 0 })
  }

  build(): void {
    Column() {
      Scroll() {
        Column({ space: 16 }) {
          this.header()
          if (this.selectedTab === 0) {
            this.cpuCard()
            this.metricCard('运行内存', `${this.memory.percent.toFixed(1)}%`,
              `已用 ${this.formatBytes(this.memory.used)} · 可用 ${this.formatBytes(this.memory.available)} · 总量 ${this.formatBytes(this.memory.total)}`,
              this.memory.percent)
            this.batteryCard()
          } else if (this.selectedTab === 1) {
            this.metricCard('运行内存', `${this.memory.percent.toFixed(1)}%`,
              `已用 ${this.formatBytes(this.memory.used)} · 可用 ${this.formatBytes(this.memory.available)}`,
              this.memory.percent)
          } else {
            this.batteryCard()
          }
        }
        .width('100%')
        .padding({ left: 16, right: 16, top: 16, bottom: 24 })
      }
      .layoutWeight(1)

      Tabs({ barPosition: BarPosition.End }) {
        TabContent() { Text('总览') }.tabBar('总览')
        TabContent() { Text('内存') }.tabBar('内存')
        TabContent() { Text('电池') }.tabBar('电池')
      }
      .barMode(BarMode.Fixed)
      .onChange((index: number) => this.selectedTab = index)
      .height(64)
      .backgroundColor(Color.White)
    }
    .width('100%')
    .height('100%')
    .backgroundColor('#F1F5F9')
  }
}
