import { Component } from '@angular/core';
import { ErrorCode } from '@shared/components/error-code/error-code';
import { TranslatePipe } from '@ngx-translate/core';

@Component({
  selector: 'app-error-500',
  template: `
    <error-code
      code="500"
      [title]="'errpage.e500_title' | translate"
      [message]="'errpage.e500_msg' | translate"
    />
  `,
  imports: [ErrorCode, TranslatePipe],
})
export class Error500 {}
